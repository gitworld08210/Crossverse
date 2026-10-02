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
import com.linkup.core.protocol.FileReassembler
import java.io.File
import java.util.Base64
import java.util.UUID
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
 * Receiving: chunks are reassembled by their transfer id (via the shared,
 * unit-tested :core [FileReassembler]), verified against the meta's sha256, and
 * saved to MediaStore (photos) or app-scoped storage. A failed hash check
 * discards the transfer. Keying reassembly on the transfer id lets two
 * concurrent transfers in one pairing complete without corrupting each other.
 */
class FileShare(
    private val context: Context,
    private val pairing: PairingManager,
    private val router: TransportRouter
) {

    /**
     * Transport-independent chunk reassembly lives in :core so it is unit-tested
     * on the JVM. [FileReassembler] is not thread-safe, so every call is guarded
     * by [reassemblyLock]; inbound envelopes can arrive on multiple transports.
     */
    private val reassembler = FileReassembler()
    private val reassemblyLock = Any()

    /** Completed items available to the Inbox UI. */
    private val _received = ArrayList<ReceivedItem>()
    val received: List<ReceivedItem> get() = synchronized(_received) { _received.toList() }

    data class ReceivedItem(val name: String, val mimeType: String, val savedPath: String)

    /**
     * Send [bytes] as a file named [name] of [mimeType] to all paired devices as
     * [kind]. A fresh [transferId] tags the meta and every chunk so concurrent
     * transfers never collide on the receiver. Meta first, then chunks of
     * [chunkSize] bytes each.
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

        val transferId = UUID.randomUUID().toString()
        val sha = FileReassembler.sha256Hex(bytes)
        val totalChunks = if (bytes.isEmpty()) 1 else (bytes.size + chunkSize - 1) / chunkSize
        val meta = FileMetaPayload(
            transferId = transferId,
            name = name,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha
        )

        for (paired in devices) {
            val key = pairing.linkKey(paired.pairingId) ?: continue

            router.send(sealMeta(paired, key, meta, kind))

            var index = 0
            var offset = 0
            while (offset < bytes.size || (bytes.isEmpty() && index == 0)) {
                val end = minOf(offset + chunkSize, bytes.size)
                val slice = bytes.copyOfRange(offset, end)
                val chunk = FileChunkPayload(
                    transferId = transferId,
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
     * in). Reassembly and integrity checking are delegated to the :core
     * [FileReassembler], keyed on the pairing + transfer id. When the transfer
     * completes and verifies, the content is saved and the resulting
     * [ReceivedItem] is returned, else null.
     */
    fun onEnvelope(envelope: Envelope): ReceivedItem? {
        if (envelope.kind !in FILE_KINDS) return null
        val key = pairing.linkKey(envelope.pairingId) ?: return null
        val plaintext = runCatching { EnvelopeCodec.openEnvelope(key, envelope) }.getOrNull()
            ?: return null
        val json = String(plaintext, Charsets.UTF_8)

        parseMeta(json)?.let { meta ->
            synchronized(reassemblyLock) { reassembler.onMeta(envelope.pairingId, meta) }
            return null
        }

        val chunk = parseChunk(json) ?: return null
        val result = synchronized(reassemblyLock) { reassembler.onChunk(envelope.pairingId, chunk) }
        return when (result) {
            is FileReassembler.ChunkResult.Complete -> {
                save(result.meta, result.bytes)?.also { item ->
                    synchronized(_received) { _received.add(item) }
                }
            }
            else -> null // Incomplete / Unknown / IntegrityFailed: nothing to surface.
        }
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
        runCatching { FileMetaPayload.fromJson(json) }.getOrNull()
            ?.takeIf { it.sha256.isNotEmpty() && it.transferId.isNotEmpty() }

    private fun parseChunk(json: String): FileChunkPayload? =
        runCatching { FileChunkPayload.fromJson(json) }.getOrNull()
            ?.takeIf { it.totalChunks > 0 && it.transferId.isNotEmpty() }

    companion object {
        /** 256 KiB plaintext per chunk, comfortably under :core Framing's 16 MiB cap. */
        const val DEFAULT_CHUNK_SIZE: Int = 256 * 1024
        private val FILE_KINDS = setOf(TransferKind.FILE, TransferKind.PHOTO, TransferKind.SCREENSHOT)
    }
}
