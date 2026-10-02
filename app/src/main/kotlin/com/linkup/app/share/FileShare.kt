package com.linkup.app.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.linkup.app.pairing.PairingManager
import com.linkup.app.transport.TransportRouter
import com.linkup.core.model.PairedDevice
import com.linkup.core.model.TransferKind
import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.EnvelopeCodec
import com.linkup.core.protocol.FileChunkPayload
import com.linkup.core.protocol.FileMetaPayload
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * File / photo / screenshot sharing.
 *
 * Sending: the chosen content (from the SAF document picker or the share-target
 * intent, or an in-memory screenshot PNG) is hashed, a sealed [FileMetaPayload]
 * is sent first, then the bytes are split into sealed [FileChunkPayload]s. Every
 * chunk is an ordinary end-to-end-encrypted [Envelope], so files traverse the
 * LAN or the relay identically and the cloud never sees content.
 *
 * Receiving: chunks are reassembled by name, verified against the meta's sha256,
 * and saved to MediaStore (photos) or app-scoped storage. A failed hash check
 * discards the transfer.
 */
class FileShare(
    private val context: Context,
    private val pairing: PairingManager,
    private val router: TransportRouter
) {

    /** In-flight inbound transfers keyed by "pairingId:name". */
    private val inbound = ConcurrentHashMap<String, InboundTransfer>()

    /** Completed items available to the Inbox UI. */
    private val _received = ArrayList<ReceivedItem>()
    val received: List<ReceivedItem> get() = synchronized(_received) { _received.toList() }

    data class ReceivedItem(val name: String, val mimeType: String, val savedPath: String)

    private class InboundTransfer(
        val meta: FileMetaPayload,
        val chunks: Array<ByteArray?>
    ) {
        var receivedCount: Int = 0
    }

    /**
     * Send [bytes] as a file named [name] of [mimeType] to all paired devices as
     * [kind]. Meta first, then chunks of [chunkSize] bytes each.
     */
    suspend fun sendBytes(
        name: String,
        mimeType: String,
        bytes: ByteArray,
        kind: TransferKind = TransferKind.FILE,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ) {
        val devices = pairing.pairedDevices()
        if (devices.isEmpty()) return

        val sha = sha256Hex(bytes)
        val totalChunks = if (bytes.isEmpty()) 1 else (bytes.size + chunkSize - 1) / chunkSize
        val meta = FileMetaPayload(name = name, mimeType = mimeType, sizeBytes = bytes.size.toLong(), sha256 = sha)

        for (paired in devices) {
            val key = pairing.linkKey(paired.pairingId) ?: continue

            router.send(sealMeta(paired, key, meta, kind))

            var index = 0
            var offset = 0
            while (offset < bytes.size || (bytes.isEmpty() && index == 0)) {
                val end = minOf(offset + chunkSize, bytes.size)
                val slice = bytes.copyOfRange(offset, end)
                val chunk = FileChunkPayload(
                    index = index,
                    totalChunks = totalChunks,
                    dataB64 = Base64.getEncoder().encodeToString(slice)
                )
                router.send(sealChunk(paired, key, chunk, kind))
                offset = end
                index++
                if (bytes.isEmpty()) break
            }
        }
    }

    /**
     * Handle an incoming FILE/PHOTO/SCREENSHOT [envelope]. The payload is either
     * a [FileMetaPayload] (starts a transfer) or a [FileChunkPayload] (fills it
     * in). When the last chunk arrives the content is sha256-verified and saved;
     * the resulting [ReceivedItem] is returned, else null.
     */
    fun onEnvelope(envelope: Envelope): ReceivedItem? {
        if (envelope.kind !in FILE_KINDS) return null
        val key = pairing.linkKey(envelope.pairingId) ?: return null
        val plaintext = runCatching { EnvelopeCodec.openEnvelope(key, envelope) }.getOrNull()
            ?: return null
        val json = String(plaintext, Charsets.UTF_8)

        parseMeta(json)?.let { meta ->
            val transferKey = "${envelope.pairingId}:${meta.name}"
            inbound[transferKey] = InboundTransfer(meta, arrayOfNulls(maxOf(1, estimateChunks(meta))))
            return null
        }

        val chunk = parseChunk(json) ?: return null
        // A chunk doesn't name its file, so attribute it to the only/last
        // in-flight transfer for this pairing.
        val entry = inbound.entries.firstOrNull { it.key.startsWith("${envelope.pairingId}:") }
            ?: return null
        val transfer = entry.value

        if (transfer.chunks.size != chunk.totalChunks) {
            // Re-size now that the real chunk count is known.
            val resized = arrayOfNulls<ByteArray>(chunk.totalChunks)
            System.arraycopy(transfer.chunks, 0, resized, 0, minOf(transfer.chunks.size, resized.size))
            val replacement = InboundTransfer(transfer.meta, resized).also { it.receivedCount = transfer.receivedCount }
            inbound[entry.key] = replacement
            return storeIfComplete(entry.key, replacement, chunk)
        }
        return storeIfComplete(entry.key, transfer, chunk)
    }

    private fun storeIfComplete(
        transferKey: String,
        transfer: InboundTransfer,
        chunk: FileChunkPayload
    ): ReceivedItem? {
        if (chunk.index in transfer.chunks.indices && transfer.chunks[chunk.index] == null) {
            transfer.chunks[chunk.index] = Base64.getDecoder().decode(chunk.dataB64)
            transfer.receivedCount++
        }
        if (transfer.receivedCount < transfer.chunks.size) return null

        val full = transfer.chunks.fold(ByteArray(0)) { acc, part -> acc + (part ?: ByteArray(0)) }
        if (sha256Hex(full) != transfer.meta.sha256) {
            inbound.remove(transferKey)
            return null // integrity check failed: discard.
        }
        val saved = save(transfer.meta, full)
        inbound.remove(transferKey)
        return saved?.also { item -> synchronized(_received) { _received.add(item) } }
    }

    private fun save(meta: FileMetaPayload, bytes: ByteArray): ReceivedItem? {
        return if (meta.mimeType.startsWith("image/")) saveImage(meta, bytes) else saveFile(meta, bytes)
    }

    private fun saveImage(meta: FileMetaPayload, bytes: ByteArray): ReceivedItem? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, meta.name)
            put(MediaStore.Images.Media.MIME_TYPE, meta.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Linkup")
            }
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        return ReceivedItem(meta.name, meta.mimeType, uri.toString())
    }

    private fun saveFile(meta: FileMetaPayload, bytes: ByteArray): ReceivedItem {
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val file = File(dir, meta.name)
        file.writeBytes(bytes)
        return ReceivedItem(meta.name, meta.mimeType, file.absolutePath)
    }

    private fun sealMeta(paired: PairedDevice, key: SecretKey, meta: FileMetaPayload, kind: TransferKind) =
        EnvelopeCodec.sealEnvelope(
            linkKey = key,
            from = pairing.deviceId,
            to = paired.device.id,
            pairingId = paired.pairingId,
            kind = kind,
            plaintextJson = meta.toJson()
        )

    private fun sealChunk(paired: PairedDevice, key: SecretKey, chunk: FileChunkPayload, kind: TransferKind) =
        EnvelopeCodec.sealEnvelope(
            linkKey = key,
            from = pairing.deviceId,
            to = paired.device.id,
            pairingId = paired.pairingId,
            kind = kind,
            plaintextJson = chunk.toJson()
        )

    private fun parseMeta(json: String): FileMetaPayload? =
        runCatching { FileMetaPayload.fromJson(json) }.getOrNull()?.takeIf { it.sha256.isNotEmpty() }

    private fun parseChunk(json: String): FileChunkPayload? =
        runCatching { FileChunkPayload.fromJson(json) }.getOrNull()?.takeIf { it.totalChunks > 0 }

    private fun estimateChunks(meta: FileMetaPayload): Int {
        if (meta.sizeBytes <= 0) return 1
        return ((meta.sizeBytes + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE).toInt()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** 256 KiB plaintext per chunk, comfortably under :core Framing's 16 MiB cap. */
        const val DEFAULT_CHUNK_SIZE: Int = 256 * 1024
        private val FILE_KINDS = setOf(TransferKind.FILE, TransferKind.PHOTO, TransferKind.SCREENSHOT)
    }
}
