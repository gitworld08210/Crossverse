package com.linkup.core.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing for the LAN direct-socket transport.
 *
 * Each frame is a 4-byte big-endian unsigned length followed by exactly that
 * many bytes of UTF-8 JSON (typically an [Envelope]). TCP is a byte stream with
 * no message boundaries, so an explicit length prefix lets the receiver know
 * where one envelope ends and the next begins.
 *
 * The relay (WebSocket) path does NOT use this framing: WebSocket already
 * delivers discrete text frames. Both paths carry the identical JSON bytes.
 */
object Framing {

    /**
     * Maximum accepted frame size (16 MiB). Guards a reader against a malicious
     * or corrupt length prefix that would otherwise try to allocate a huge
     * buffer. Large transfers are chunked (see [FileChunkPayload]) well under
     * this bound.
     */
    const val MAX_FRAME_SIZE: Int = 16 * 1024 * 1024

    /**
     * Write [payload] as a single length-prefixed frame to [out]. Throws
     * [IllegalArgumentException] if the payload exceeds [MAX_FRAME_SIZE].
     */
    fun writeFrame(out: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_SIZE) {
            "frame too large: ${payload.size} > $MAX_FRAME_SIZE"
        }
        val len = payload.size
        val header = byteArrayOf(
            (len ushr 24 and 0xFF).toByte(),
            (len ushr 16 and 0xFF).toByte(),
            (len ushr 8 and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
        out.write(header)
        out.write(payload)
        out.flush()
    }

    /**
     * Read a single length-prefixed frame from [input].
     *
     * @throws EOFException if the stream ends before a full frame is read.
     * @throws IOException if the declared length is negative or exceeds
     *   [MAX_FRAME_SIZE] (oversized-frame guard).
     */
    fun readFrame(input: InputStream): ByteArray {
        val header = readFully(input, 4)
        val len = (header[0].toInt() and 0xFF shl 24) or
            (header[1].toInt() and 0xFF shl 16) or
            (header[2].toInt() and 0xFF shl 8) or
            (header[3].toInt() and 0xFF)
        if (len < 0 || len > MAX_FRAME_SIZE) {
            throw IOException("invalid/oversized frame length: $len")
        }
        return readFully(input, len)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) {
                throw EOFException("stream ended after $read of $n bytes")
            }
            read += r
        }
        return buf
    }
}
