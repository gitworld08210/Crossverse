package com.linkup.app.transport

import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.WireJson
import com.linkup.core.transport.PairingSubscriptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * The relay authenticates each socket to a single ([deviceId], [pairingId]), so
 * a device in MORE THAN ONE pairing (phone↔tablet and phone↔laptop as distinct
 * pairings) needs one socket per pairing. This client therefore multiplexes: it
 * maintains one auto-reconnecting connect loop per pairing id and sends an
 * envelope over the socket matching its [Envelope.pairingId]. Each loop uses
 * capped exponential backoff so a device that roams or sleeps re-establishes the
 * connection and (thanks to the relay's offline queue) catches up.
 *
 * The subscribed pairing set is DYNAMIC. The ids passed in are only the ones
 * known at construction (zero on a fresh install); [addPairing] launches a new
 * connect loop at runtime so a pairing created or accepted AFTER [connect] --
 * including the user's very first pairing -- gets its own relay socket WITHOUT a
 * process restart. That is exactly the far-away/no-LAN case where the relay is
 * the only path, so the socket must come up immediately. [addPairing] is
 * idempotent (via [PairingSubscriptions]): re-adding an already-subscribed
 * pairing is a no-op and never spawns a duplicate loop.
 */
class RelayClient(
    private val relayUrl: String,
    private val deviceId: String,
    pairingIds: List<String>,
    private val client: HttpClient = defaultClient()
) {

    /** Convenience single-pairing constructor (kept for simple callers/tests). */
    constructor(
        relayUrl: String,
        deviceId: String,
        pairingId: String,
        client: HttpClient = defaultClient()
    ) : this(relayUrl, deviceId, listOf(pairingId), client)

    private val _incoming = MutableSharedFlow<Envelope>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /** Envelopes received from the relay (already sealed; open via :core). */
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    // One live session per pairing id, used by [send]. A value is absent while
    // that pairing's socket is disconnected / reconnecting.
    private val sessions =
        java.util.concurrent.ConcurrentHashMap<String, io.ktor.client.plugins.websocket.DefaultClientWebSocketSession>()

    // Dynamic set of pairing ids we hold (or are establishing) a socket for.
    // Starts with the ids known at construction and grows via [addPairing].
    private val subscriptions = PairingSubscriptions(pairingIds)

    // The scope [connect] runs in, captured so [addPairing] can launch a new
    // per-pairing connect loop into the SAME lifetime. Null until [connect].
    @Volatile
    private var connectScope: CoroutineScope? = null

    /**
     * Connect a socket for every currently-subscribed pairing and keep them all
     * alive, reconnecting with backoff until the calling coroutine is cancelled.
     * Received envelopes from any pairing are emitted on [incoming]. Suspends for
     * the lifetime of the connection loops, so launch it in the service scope.
     *
     * After this returns control to its own body, [addPairing] can inject
     * additional loops into the same scope at runtime.
     */
    suspend fun connect() {
        kotlinx.coroutines.coroutineScope {
            connectScope = this
            for (pairingId in subscriptions.snapshot()) {
                launch { connectPairing(pairingId) }
            }
            // Keep the scope alive even if we started with no pairings (fresh
            // install) so a later [addPairing] has a live scope to launch into.
            launch { kotlinx.coroutines.awaitCancellation() }
        }
    }

    /**
     * Subscribe to [pairingId] at runtime: if it is not already subscribed,
     * launch a new auto-reconnecting connect loop for it into the [connect]
     * scope. Idempotent -- re-adding an existing pairing does nothing and never
     * spawns a duplicate loop. Call from the pairing-completion paths
     * (`createPairing` / `acceptPairing` / `recordPeer`) so a device that pairs
     * while running immediately gets a relay socket for the new pairing without
     * a restart.
     *
     * Returns true if a new loop was launched, false if the pairing was already
     * subscribed or [connect] has not been called yet (in which case the pairing
     * is still recorded and will be connected when [connect] runs).
     */
    fun addPairing(pairingId: String): Boolean {
        val isNew = subscriptions.add(pairingId)
        if (!isNew) return false
        val scope = connectScope ?: return false
        scope.launch { connectPairing(pairingId) }
        return true
    }

    private suspend fun connectPairing(pairingId: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (coroutineContext.isActive) {
            try {
                val ws = client.webSocketSession(urlString = relayUrl)
                sessions[pairingId] = ws
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
                sessions.remove(pairingId)
            }

            if (!coroutineContext.isActive) break
            kotlinx.coroutines.delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Send a sealed [envelope] to the relay over the socket for its
     * [Envelope.pairingId]. Returns true if a live session for that pairing
     * accepted it, false if that pairing is currently disconnected (the caller
     * may retry or rely on the LAN path / relay offline queue).
     */
    suspend fun send(envelope: Envelope): Boolean {
        val ws = sessions[envelope.pairingId] ?: return false
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
