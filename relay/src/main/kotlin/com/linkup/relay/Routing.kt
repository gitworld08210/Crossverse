package com.linkup.relay

import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.WireJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable

/**
 * The first frame a client must send after opening the `/ws` connection. It
 * authenticates the socket to a single ([deviceId], [pairingId]). Every
 * subsequent [Envelope] on this socket is checked against it, so a client can
 * only ever speak as the device it handshaked as and only inside its own
 * pairing.
 */
@Serializable
data class Hello(val deviceId: String, val pairingId: String)

/**
 * Wire up the relay's HTTP + WebSocket routes.
 *
 * `GET /health` is a trivial liveness probe for deploy/orchestration checks.
 *
 * `WS /ws` is the device-to-device relay channel. A device sends a [Hello], we
 * register its session (flushing any envelopes queued while it was offline),
 * then we loop forwarding each sealed [Envelope] it sends to the other devices
 * in the same pairing via [RelayHub.routeEnvelope]. The relay never decrypts:
 * it validates routing metadata and moves opaque ciphertext.
 */
fun Application.module(hub: RelayHub = RelayHub()) {
    install(WebSockets)

    routing {
        get("/health") {
            call.respondText("OK", status = HttpStatusCode.OK)
        }

        webSocket("/ws") {
            // --- Handshake: the first text frame must be a valid Hello. ---
            val helloFrame = incoming.receive() as? Frame.Text
                ?: run {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "expected Hello frame"))
                    return@webSocket
                }

            val hello = try {
                WireJson.json.decodeFromString(Hello.serializer(), helloFrame.readText())
            } catch (_: Exception) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "malformed Hello"))
                return@webSocket
            }

            if (hello.deviceId.isBlank() || hello.pairingId.isBlank()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid Hello"))
                return@webSocket
            }

            val deviceId = hello.deviceId
            val pairingId = hello.pairingId

            // Register this socket and immediately flush its offline backlog so a
            // device that was asleep / far away catches up the moment it returns.
            val backlog = hub.register(pairingId, deviceId) { envelope ->
                outgoing.send(Frame.Text(envelope.toJson()))
            }
            backlog.forEach { outgoing.send(Frame.Text(it.toJson())) }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue

                    val envelope = try {
                        Envelope.fromJson(frame.readText())
                    } catch (_: Exception) {
                        // Ignore unparsable frames rather than dropping the socket.
                        continue
                    }

                    // A client may only speak as the device it handshaked as, and
                    // only within its handshaked pairing. Reject spoofed frames.
                    if (envelope.fromDeviceId != deviceId || envelope.pairingId != pairingId) {
                        continue
                    }

                    hub.routeEnvelope(envelope)
                }
            } finally {
                hub.unregister(pairingId, deviceId)
            }
        }
    }
}
