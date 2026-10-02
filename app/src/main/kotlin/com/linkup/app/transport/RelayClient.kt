package com.linkup.app.transport

import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.WireJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

/**
 * Mirror of the relay's handshake frame (see relay `Routing.Hello`). The first
 * text frame a client sends after opening `/ws` must be this exact JSON shape:
 * `{"deviceId": "...", "pairingId": "..."}`. It authenticates the socket to a
 * single device inside a single pairing.
 */
@Serializable
data class Hello(val deviceId: String, val pairingId: String)

/**
 * Ktor OkHttp WebSocket client for the Linkup cloud relay.
 *
 * This is the FAR path: when the phone is on mobile data or otherwise not on the
 * same Wi-Fi as its tablet/laptop, both devices stay connected to the relay and
 * exchange the SAME sealed [Envelope]s they would send over the LAN. The relay
 * only ever sees opaque ciphertext (it routes by [Envelope.pairingId] /
 * [Envelope.fromDeviceId]); nothing is decryptable here or there.
 *
 * [connect] runs an auto-reconnect loop with capped exponential backoff so a
 * device that roams between networks or sleeps re-establishes the connection and
 * (thanks to the relay's offline queue) catches up on anything it missed.
 */
class RelayClient(
    private val relayUrl: String,
    private val deviceId: String,
    private val pairingId: String,
    private val client: HttpClient = defaultClient()
) {

    private val _incoming = MutableSharedFlow<Envelope>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /** Envelopes received from the relay (already sealed; open via :core). */
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    // The live session, used by [send]. Null while disconnected / reconnecting.
    @Volatile
    private var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null

    /**
     * Connect to the relay and keep the connection alive, reconnecting with
     * backoff until the calling coroutine is cancelled. Received envelopes are
     * emitted on [incoming]. This function suspends for the lifetime of the
     * connection loop, so launch it in the service scope.
     */
    suspend fun connect() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (coroutineContext.isActive) {
            try {
                val ws = client.webSocketSession(urlString = relayUrl)
                session = ws
                // Handshake: send Hello first, exactly as the relay expects.
                ws.send(Frame.Text(WireJson.json.encodeToString(Hello.serializer(), Hello(deviceId, pairingId))))
                backoffMs = INITIAL_BACKOFF_MS // connected cleanly: reset backoff.

                for (frame in ws.incoming) {
                    if (frame !is Frame.Text) continue
                    val envelope = try {
                        Envelope.fromJson(frame.readText())
                    } catch (_: Exception) {
                        continue // ignore unparsable frames, keep the socket.
                    }
                    _incoming.emit(envelope)
                }
            } catch (_: Exception) {
                // Fall through to backoff + retry below.
            } finally {
                session = null
            }

            if (!coroutineContext.isActive) break
            kotlinx.coroutines.delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Send a sealed [envelope] to the relay. Returns true if a live session
     * accepted it, false if currently disconnected (the caller may retry or rely
     * on the LAN path / relay offline queue).
     */
    suspend fun send(envelope: Envelope): Boolean {
        val ws = session ?: return false
        return try {
            ws.send(Frame.Text(envelope.toJson()))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Observe incoming envelopes as a cold [Flow] (shares the hot [incoming]). */
    fun incomingFlow(): Flow<Envelope> = incoming

    /** Release the underlying HTTP client. Call when the service shuts down. */
    fun close() {
        client.close()
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** OkHttp-engine client with the WebSockets plugin installed. */
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(WebSockets)
        }
    }
}
