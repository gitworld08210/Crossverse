package com.linkup.app.transport

import com.linkup.core.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The ONE send/receive surface for the whole app.
 *
 * The rest of the app (clipboard, file share, screenshots, the SyncService)
 * never talks to a specific transport. It calls [send] and collects [incoming],
 * and the router decides the path:
 *
 *  - NEAR (same Wi-Fi): if [LanTransport] has discovered a peer, the envelope
 *    goes directly over the LAN socket — fast, no cloud.
 *  - FAR (mobile data / different network): otherwise it falls back to the
 *    [RelayClient] WebSocket so a remote phone still syncs with the tablet.
 *
 * Both paths carry the identical sealed [Envelope], so switching transports is
 * invisible to callers and no plaintext ever leaves the device for the relay.
 */
class TransportRouter(
    private val lan: LanTransport,
    private val relay: RelayClient,
    private val scope: CoroutineScope
) {

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 128)

    /** Unified stream of received envelopes from WHICHEVER transport delivered. */
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    /**
     * Start both transports and fan their incoming envelopes into the single
     * [incoming] flow. Call once from [com.linkup.app.service.SyncService].
     */
    fun start() {
        lan.start()
        scope.launch { relay.connect() }
        scope.launch {
            merge(lan.incoming, relay.incoming).collect { envelope ->
                _incoming.emit(envelope)
            }
        }
    }

    /**
     * Send a sealed [envelope], preferring the LAN when a peer is present and
     * falling back to the relay otherwise. If the preferred path fails to
     * deliver, the other path is attempted so a transient failure doesn't drop
     * the message.
     */
    suspend fun send(envelope: Envelope) {
        if (lan.hasPeer) {
            if (lan.send(envelope)) return
            // LAN said it had a peer but delivery failed: fall back to relay.
            relay.send(envelope)
        } else {
            if (relay.send(envelope)) return
            // Relay disconnected: last-ditch attempt over the LAN.
            lan.send(envelope)
        }
    }

    /** Observe incoming envelopes as a [Flow]. */
    fun incomingFlow(): Flow<Envelope> = incoming

    /** Stop both transports. */
    fun stop() {
        lan.stop()
        relay.close()
    }
}
