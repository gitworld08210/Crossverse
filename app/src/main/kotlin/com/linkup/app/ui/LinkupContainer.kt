package com.linkup.app.ui

import android.content.Context
import com.linkup.app.BuildConfig
import com.linkup.app.clipboard.ClipboardSync
import com.linkup.app.pairing.PairingHandshake
import com.linkup.app.pairing.PairingManager
import com.linkup.app.share.FileShare
import com.linkup.app.transport.LanTransport
import com.linkup.app.transport.RelayClient
import com.linkup.app.transport.TransportRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tiny process-wide service locator so the Compose screens and the
 * [com.linkup.app.service.SyncService] share one [PairingManager] (hence one
 * encrypted store + stable device id) and one [TransportRouter].
 *
 * This is intentionally minimal (no DI framework) to keep Phase 1 reviewable.
 */
object LinkupContainer {

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var pairingRef: PairingManager? = null

    @Volatile
    private var routerRef: TransportRouter? = null

    @Volatile
    private var clipboardRef: ClipboardSync? = null

    @Volatile
    private var fileShareRef: FileShare? = null

    @Volatile
    private var handshakeRef: PairingHandshake? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun pairing(context: Context): PairingManager {
        init(context)
        return pairingRef ?: synchronized(this) {
            pairingRef ?: PairingManager(appContext!!).also { pairingRef = it }
        }
    }

    /**
     * The shared [TransportRouter]. Started lazily on first use so UI 'send'
     * actions have a live LAN + relay path even before the service callback runs.
     */
    fun router(context: Context): TransportRouter {
        init(context)
        return routerRef ?: synchronized(this) {
            routerRef ?: run {
                val pm = pairing(appContext!!)
                val relay = RelayClient(
                    relayUrl = BuildConfig.RELAY_URL,
                    deviceId = pm.deviceId,
                    pairingIds = pm.pairedDevices().map { it.pairingId }.distinct()
                        .ifEmpty { listOf(pm.deviceId) }
                )
                val lan = LanTransport(appContext!!, pm.deviceId, scope)
                TransportRouter(lan, relay, scope).also {
                    it.start()
                    // Open a far-path socket as soon as the UI creates/accepts a
                    // pairing, so remote (no-LAN) sync works without a restart.
                    pm.onPairingSubscribed = it::subscribePairing
                    routerRef = it
                }
            }
        }
    }

    fun clipboard(context: Context): ClipboardSync {
        init(context)
        return clipboardRef ?: synchronized(this) {
            clipboardRef ?: ClipboardSync(appContext!!, pairing(appContext!!), router(appContext!!))
                .also { clipboardRef = it }
        }
    }

    fun fileShare(context: Context): FileShare {
        init(context)
        return fileShareRef ?: synchronized(this) {
            fileShareRef ?: FileShare(appContext!!, pairing(appContext!!), router(appContext!!))
                .also { fileShareRef = it }
        }
    }

    fun pairingHandshake(context: Context): PairingHandshake {
        init(context)
        return handshakeRef ?: synchronized(this) {
            handshakeRef ?: PairingHandshake(pairing(appContext!!), router(appContext!!))
                .also { handshakeRef = it }
        }
    }
}
