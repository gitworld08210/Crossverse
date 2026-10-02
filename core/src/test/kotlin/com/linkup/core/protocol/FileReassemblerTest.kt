package com.linkup.core.protocol

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileReassemblerTest {

    private val chunkSize = FileReassembler.DEFAULT_CHUNK_SIZE

    private fun chunksFor(transferId: String, bytes: ByteArray): List<FileChunkPayload> {
        val total = if (bytes.isEmpty()) 1 else (bytes.size + chunkSize - 1) / chunkSize
        val out = ArrayList<FileChunkPayload>()
        var index = 0
        var offset = 0
        while (offset < bytes.size || (bytes.isEmpty() && index == 0)) {
            val end = minOf(offset + chunkSize, bytes.size)
            val slice = bytes.copyOfRange(offset, end)
            out.add(
                FileChunkPayload(
                    transferId = transferId,
                    index = index,
                    totalChunks = total,
                    dataB64 = Base64.getEncoder().encodeToString(slice)
                )
            )
            offset = end
            index++
            if (bytes.isEmpty()) break
        }
        return out
    }

    private fun metaFor(transferId: String, name: String, bytes: ByteArray) = FileMetaPayload(
        transferId = transferId,
        name = name,
        mimeType = "application/octet-stream",
        sizeBytes = bytes.size.toLong(),
        sha256 = FileReassembler.sha256Hex(bytes)
    )

    @Test
    fun `reassembles a single multi-chunk transfer and verifies the digest`() {
        val r = FileReassembler()
        val bytes = ByteArray(chunkSize * 2 + 123) { (it % 251).toByte() }
        val meta = metaFor("t1", "big.bin", bytes)
        r.onMeta("pair-1", meta)

        val chunks = chunksFor("t1", bytes)
        for (i in 0 until chunks.size - 1) {
            assertEquals(FileReassembler.ChunkResult.Incomplete, r.onChunk("pair-1", chunks[i]))
        }
        val result = r.onChunk("pair-1", chunks.last())
        assertTrue(result is FileReassembler.ChunkResult.Complete)
        assertTrue(bytes.contentEquals(result.bytes))
        assertEquals("big.bin", result.meta.name)
    }

    /**
     * Regression test for the concurrent-transfer attribution bug: two transfers
     * in the SAME pairing, fully interleaved. Without a transferId on each chunk
     * the reassembler attributed every chunk to "the first in-flight transfer",
     * mixing the two payloads so BOTH failed their SHA-256 check. Keying on the
     * transferId keeps them apart.
     */
    @Test
    fun `two concurrent transfers in one pairing do not corrupt each other`() {
        val r = FileReassembler()
        val a = ByteArray(chunkSize + 7) { (it % 97).toByte() }
        val b = ByteArray(chunkSize + 50) { ((it * 3) % 211).toByte() }
        val metaA = metaFor("A", "a.bin", a)
        val metaB = metaFor("B", "b.bin", b)

        r.onMeta("pair-1", metaA)
        r.onMeta("pair-1", metaB)

        val ca = chunksFor("A", a)
        val cb = chunksFor("B", b)
        assertEquals(2, ca.size)
        assertEquals(2, cb.size)

        // Interleave: A0, B0, B1 (completes B), A1 (completes A).
        assertEquals(FileReassembler.ChunkResult.Incomplete, r.onChunk("pair-1", ca[0]))
        assertEquals(FileReassembler.ChunkResult.Incomplete, r.onChunk("pair-1", cb[0]))

        val resB = r.onChunk("pair-1", cb[1])
        assertTrue(resB is FileReassembler.ChunkResult.Complete, "B should complete intact")
        assertTrue(b.contentEquals(resB.bytes))
        assertEquals("b.bin", resB.meta.name)

        val resA = r.onChunk("pair-1", ca[1])
        assertTrue(resA is FileReassembler.ChunkResult.Complete, "A should complete intact")
        assertTrue(a.contentEquals(resA.bytes))
        assertEquals("a.bin", resA.meta.name)
    }

    @Test
    fun `a chunk with no matching meta is reported Unknown`() {
        val r = FileReassembler()
        val chunk = chunksFor("ghost", "hi".toByteArray()).first()
        assertEquals(FileReassembler.ChunkResult.Unknown, r.onChunk("pair-1", chunk))
    }

    @Test
    fun `a corrupted chunk fails the integrity check`() {
        val r = FileReassembler()
        val bytes = ByteArray(chunkSize + 10) { (it % 255).toByte() }
        val meta = metaFor("t", "x.bin", bytes)
        r.onMeta("pair-1", meta)
        val chunks = chunksFor("t", bytes).toMutableList()
        // Tamper with the last chunk's content.
        val last = chunks.last()
        chunks[chunks.size - 1] = last.copy(
            dataB64 = Base64.getEncoder().encodeToString(ByteArray(5) { 0 })
        )
        for (i in 0 until chunks.size - 1) r.onChunk("pair-1", chunks[i])
        val result = r.onChunk("pair-1", chunks.last())
        assertTrue(result is FileReassembler.ChunkResult.IntegrityFailed)
    }

    @Test
    fun `empty transfer completes with a single chunk`() {
        val r = FileReassembler()
        val bytes = ByteArray(0)
        val meta = metaFor("e", "empty.bin", bytes)
        r.onMeta("pair-1", meta)
        val result = r.onChunk("pair-1", chunksFor("e", bytes).first())
        assertTrue(result is FileReassembler.ChunkResult.Complete)
        assertEquals(0, result.bytes.size)
    }

    @Test
    fun `same transferId in different pairings stays isolated`() {
        val r = FileReassembler()
        val a = ByteArray(chunkSize + 1) { 1 }
        val b = ByteArray(chunkSize + 2) { 2 }
        r.onMeta("pair-1", metaFor("shared", "a.bin", a))
        r.onMeta("pair-2", metaFor("shared", "b.bin", b))
        val ca = chunksFor("shared", a)
        val cb = chunksFor("shared", b)
        r.onChunk("pair-1", ca[0])
        r.onChunk("pair-2", cb[0])
        val resA = r.onChunk("pair-1", ca[1])
        val resB = r.onChunk("pair-2", cb[1])
        assertTrue(resA is FileReassembler.ChunkResult.Complete && a.contentEquals(resA.bytes))
        assertTrue(resB is FileReassembler.ChunkResult.Complete && b.contentEquals(resB.bytes))
    }
}
