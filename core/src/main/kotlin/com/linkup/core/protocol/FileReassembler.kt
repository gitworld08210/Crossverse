package com.linkup.core.protocol

import java.security.MessageDigest
import java.util.Base64

/**
 * Pure-JVM reassembly of chunked file/photo/screenshot transfers.
 *
 * This is the transport-independent heart of the receive path, lifted out of
 * the Android :app so it can be unit-tested on the JVM. The Android layer only
 * decrypts envelopes and decides where to save the finished bytes; everything
 * about matching chunks to a transfer, buffering them, and verifying integrity
 * lives here.
 *
 * The key correctness property: each transfer is identified by its
 * [FileMetaPayload.transferId] (echoed on every [FileChunkPayload.transferId]),
 * so two concurrent transfers in the SAME pairing never interleave. Reassembly
 * is keyed on `pairingId + transferId`, not on "the first in-flight transfer for
 * this pairing" (which silently corrupted overlapping transfers).
 *
 * This class is NOT itself thread-safe; callers that feed it from multiple
 * threads should synchronize, exactly as the Android [java.util.concurrent]
 * wrappers around it do.
 */
class FileReassembler {

    /** The outcome of feeding one chunk into the reassembler. */
    sealed interface ChunkResult {
        /** The chunk was accepted but the transfer is not complete yet. */
        object Incomplete : ChunkResult

        /** The chunk could not be attributed to a known transfer (no meta seen). */
        object Unknown : ChunkResult

        /** The transfer completed and its SHA-256 matched; [bytes] is the content. */
        data class Complete(val meta: FileMetaPayload, val bytes: ByteArray) : ChunkResult {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Complete) return false
                return meta == other.meta && bytes.contentEquals(other.bytes)
            }

            override fun hashCode(): Int = 31 * meta.hashCode() + bytes.contentHashCode()
        }

        /** The transfer completed but failed its SHA-256 check and was discarded. */
        data class IntegrityFailed(val meta: FileMetaPayload) : ChunkResult
    }

    private class InboundTransfer(val meta: FileMetaPayload, val totalChunks: Int) {
        val chunks: Array<ByteArray?> = arrayOfNulls(totalChunks)
        var receivedCount: Int = 0
    }

    // keyed on "pairingId:transferId" so distinct transfers never collide, even
    // when they share a pairing or (across pairings) a transferId.
    private val inbound = HashMap<String, InboundTransfer>()

    /**
     * Begin a transfer announced by [meta] arriving in [pairingId]. Idempotent:
     * re-announcing an in-flight transfer keeps the already-received chunks.
     */
    fun onMeta(pairingId: String, meta: FileMetaPayload) {
        val key = key(pairingId, meta.transferId)
        if (inbound.containsKey(key)) return
        inbound[key] = InboundTransfer(meta, maxOf(1, totalChunksOf(meta)))
    }

    /**
     * Feed one [chunk] arriving in [pairingId] into its transfer. Returns a
     * [ChunkResult] describing whether the transfer is now complete, still
     * incomplete, failed integrity, or could not be matched to a known transfer.
     */
    fun onChunk(pairingId: String, chunk: FileChunkPayload): ChunkResult {
        val key = key(pairingId, chunk.transferId)
        val transfer = inbound[key] ?: return ChunkResult.Unknown

        // The meta's size estimate can disagree with the real chunk count (e.g.
        // a different chunk size on the sender); trust the chunk's totalChunks.
        if (transfer.chunks.size != chunk.totalChunks && chunk.totalChunks > 0) {
            val resized = InboundTransfer(transfer.meta, chunk.totalChunks)
            val copy = minOf(transfer.chunks.size, resized.chunks.size)
            System.arraycopy(transfer.chunks, 0, resized.chunks, 0, copy)
            // Recount only the slots that survived the resize.
            resized.receivedCount = resized.chunks.take(copy).count { it != null }
            inbound[key] = resized
            return accept(key, resized, chunk)
        }
        return accept(key, transfer, chunk)
    }

    private fun accept(key: String, transfer: InboundTransfer, chunk: FileChunkPayload): ChunkResult {
        if (chunk.index in transfer.chunks.indices && transfer.chunks[chunk.index] == null) {
            transfer.chunks[chunk.index] = Base64.getDecoder().decode(chunk.dataB64)
            transfer.receivedCount++
        }
        if (transfer.receivedCount < transfer.chunks.size) return ChunkResult.Incomplete

        val full = assemble(transfer)
        inbound.remove(key)
        return if (sha256Hex(full) == transfer.meta.sha256) {
            ChunkResult.Complete(transfer.meta, full)
        } else {
            ChunkResult.IntegrityFailed(transfer.meta)
        }
    }

    private fun assemble(transfer: InboundTransfer): ByteArray {
        val size = transfer.chunks.sumOf { it?.size ?: 0 }
        val out = ByteArray(size)
        var pos = 0
        for (part in transfer.chunks) {
            if (part == null) continue
            System.arraycopy(part, 0, out, pos, part.size)
            pos += part.size
        }
        return out
    }

    private fun key(pairingId: String, transferId: String) = "$pairingId:$transferId"

    private fun totalChunksOf(meta: FileMetaPayload): Int {
        if (meta.sizeBytes <= 0) return 1
        return ((meta.sizeBytes + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE).toInt()
    }

    companion object {
        /** 256 KiB plaintext per chunk, matching the sender's default chunking. */
        const val DEFAULT_CHUNK_SIZE: Int = 256 * 1024

        /** Hex SHA-256 of [bytes], the integrity digest used on both ends. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
