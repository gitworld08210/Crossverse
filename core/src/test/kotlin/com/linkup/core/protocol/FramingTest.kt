package com.linkup.core.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class FramingTest {

    private fun roundTrip(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Framing.writeFrame(out, payload)
        val input = ByteArrayInputStream(out.toByteArray())
        return Framing.readFrame(input)
    }

    @Test
    fun `round-trips a normal payload`() {
        val payload = "hello linkup".toByteArray()
        assertContentEquals(payload, roundTrip(payload))
    }

    @Test
    fun `round-trips an empty payload`() {
        val payload = ByteArray(0)
        assertContentEquals(payload, roundTrip(payload))
    }

    @Test
    fun `round-trips a large payload`() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        assertContentEquals(payload, roundTrip(payload))
    }

    @Test
    fun `round-trips multiple frames in sequence`() {
        val a = "first".toByteArray()
        val b = "second-frame".toByteArray()
        val out = ByteArrayOutputStream()
        Framing.writeFrame(out, a)
        Framing.writeFrame(out, b)
        val input = ByteArrayInputStream(out.toByteArray())
        assertContentEquals(a, Framing.readFrame(input))
        assertContentEquals(b, Framing.readFrame(input))
    }

    @Test
    fun `writeFrame rejects oversized payload`() {
        val out = ByteArrayOutputStream()
        val tooBig = ByteArray(Framing.MAX_FRAME_SIZE + 1)
        assertFailsWith<IllegalArgumentException> {
            Framing.writeFrame(out, tooBig)
        }
    }

    @Test
    fun `readFrame rejects an oversized declared length`() {
        // Craft a header whose declared length exceeds MAX_FRAME_SIZE.
        val len = Framing.MAX_FRAME_SIZE + 1
        val header = byteArrayOf(
            (len ushr 24 and 0xFF).toByte(),
            (len ushr 16 and 0xFF).toByte(),
            (len ushr 8 and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
        val input = ByteArrayInputStream(header)
        assertFailsWith<IOException> { Framing.readFrame(input) }
    }

    @Test
    fun `readFrame throws on truncated stream`() {
        val out = ByteArrayOutputStream()
        Framing.writeFrame(out, "complete".toByteArray())
        val bytes = out.toByteArray()
        // Drop the last byte so the body is incomplete.
        val truncated = bytes.copyOf(bytes.size - 1)
        val input = ByteArrayInputStream(truncated)
        assertFailsWith<EOFException> { Framing.readFrame(input) }
    }
}
