package com.linkup.relay

import com.linkup.core.crypto.E2E
import com.linkup.core.model.TransferKind
import com.linkup.core.protocol.ClipboardPayload
import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.EnvelopeCodec
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the relay forwards sealed [Envelope]s between two remote
 * devices in the SAME pairing, isolates a device in a DIFFERENT pairing, and
 * delivers an envelope queued while a device was offline once it reconnects.
 *
 * The tests use the real :core [EnvelopeCodec] to build and open envelopes with
 * a shared link key, proving the relay only moves ciphertext end-to-end.
 */
class RelayRoutingTest {

    private val pairingSecret = ByteArray(32) { (it * 3 + 1).toByte() }
    private val linkKey = E2E.deriveLinkKey(pairingSecret, "clipboard")

    private fun hello(deviceId: String, pairingId: String): String =
        """{"deviceId":"$deviceId","pairingId":"$pairingId"}"""

    @Test
    fun `health endpoint returns 200`() = testApplication {
        application { module() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `forwards a sealed envelope to the other device in the same pairing`() =
        testApplication {
            application { module() }
            val client = createClient { install(ClientWebSockets) }

            val payload = ClipboardPayload("https://example.com/far-away-copy")
            val sealed = EnvelopeCodec.sealEnvelope(
                linkKey = linkKey,
                from = "phone-A",
                to = "tablet-B",
                pairingId = "pair-1",
                kind = TransferKind.CLIPBOARD_TEXT,
                plaintextJson = payload.toJson()
            )

            client.webSocket("/ws") {
                // B connects first and handshakes.
                send(Frame.Text(hello("tablet-B", "pair-1")))

                // A connects on a second socket and sends the envelope.
                client.webSocket("/ws") {
                    send(Frame.Text(hello("phone-A", "pair-1")))
                    send(Frame.Text(sealed.toJson()))
                }

                // B should receive the byte-identical envelope.
                val received = withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }
                val envelope = Envelope.fromJson(received)
                assertEquals(sealed, envelope)

                // And B can decrypt it with the shared link key.
                val opened = EnvelopeCodec.openEnvelope(linkKey, envelope)
                assertEquals(payload, ClipboardPayload.fromJson(String(opened, Charsets.UTF_8)))
            }
        }

    @Test
    fun `does not forward to a device in a different pairing`() = testApplication {
        application { module() }
        val client = createClient { install(ClientWebSockets) }

        val sealed = EnvelopeCodec.sealEnvelope(
            linkKey = linkKey,
            from = "phone-A",
            to = "tablet-B",
            pairingId = "pair-1",
            kind = TransferKind.CLIPBOARD_TEXT,
            plaintextJson = ClipboardPayload("secret").toJson()
        )

        client.webSocket("/ws") {
            // C is in a DIFFERENT pairing and must receive nothing.
            send(Frame.Text(hello("device-C", "pair-2")))

            client.webSocket("/ws") {
                send(Frame.Text(hello("tablet-B", "pair-1")))

                client.webSocket("/ws") {
                    send(Frame.Text(hello("phone-A", "pair-1")))
                    send(Frame.Text(sealed.toJson()))
                }

                // B (same pairing) does receive it.
                val received = withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }
                assertEquals(sealed, Envelope.fromJson(received))
            }

            // Give routing time to run, then confirm C has nothing queued:
            // isolation across pairings holds.
            delay(500)
            val leaked: ChannelResult<Frame> = incoming.tryReceive()
            assertTrue(
                leaked.isFailure,
                "device in a different pairing must not receive the envelope"
            )
        }
    }

    @Test
    fun `delivers an envelope queued while the peer was offline`() = testApplication {
        application { module() }
        val client = createClient { install(ClientWebSockets) }

        val payload = ClipboardPayload("sent while phone asleep")
        val sealed = EnvelopeCodec.sealEnvelope(
            linkKey = linkKey,
            from = "phone-A",
            to = "tablet-B",
            pairingId = "pair-offline",
            kind = TransferKind.CLIPBOARD_TEXT,
            plaintextJson = payload.toJson()
        )

        // A sends while B is NOT connected: the envelope is parked offline.
        client.webSocket("/ws") {
            send(Frame.Text(hello("phone-A", "pair-offline")))
            send(Frame.Text(sealed.toJson()))
        }

        // B connects later and must receive the backlog on handshake.
        client.webSocket("/ws") {
            send(Frame.Text(hello("tablet-B", "pair-offline")))
            val received = withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }
            val envelope = Envelope.fromJson(received)
            assertEquals(sealed, envelope)
            val opened = EnvelopeCodec.openEnvelope(linkKey, envelope)
            assertEquals(payload, ClipboardPayload.fromJson(String(opened, Charsets.UTF_8)))
        }
    }
}
