package com.linkup.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.linkup.app.BuildConfig
import com.linkup.app.R
import com.linkup.app.clipboard.ClipboardSync
import com.linkup.app.pairing.PairingHandshake
import com.linkup.app.pairing.PairingManager
import com.linkup.app.share.FileShare
import com.linkup.app.share.ScreenshotCapture
import com.linkup.app.transport.LanTransport
import com.linkup.app.transport.RelayClient
import com.linkup.app.transport.TransportRouter
import com.linkup.core.model.TransferKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps Linkup syncing.
 *
 * It owns the single [TransportRouter] (LAN + relay) and keeps the relay
 * WebSocket connected in the background, which is exactly what makes "works when
 * my phone is far away" true: even on mobile data with the screen off, the
 * foreground service holds the relay socket open so clipboard/screenshot/file
 * envelopes still arrive. Incoming envelopes are dispatched by kind to the
 * clipboard and file handlers.
 */
class SyncService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(SupervisorJob(job))

    private lateinit var pairing: PairingManager
    private lateinit var router: TransportRouter
    private lateinit var clipboardSync: ClipboardSync
    private lateinit var fileShare: FileShare
    private lateinit var screenshotCapture: ScreenshotCapture
    private lateinit var pairingHandshake: PairingHandshake

    private val binder = LocalBinder()

    /** Local binder so an in-process Activity can drive a screenshot capture. */
    inner class LocalBinder : Binder() {
        val service: SyncService get() = this@SyncService
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundNotification()

        pairing = PairingManager(applicationContext)
        val relay = RelayClient(
            relayUrl = BuildConfig.RELAY_URL,
            deviceId = pairing.deviceId,
            // One relay socket per pairing so a device in multiple pairings
            // receives far-path traffic for ALL of them. Fall back to the device
            // id when not yet paired so the socket still comes up.
            pairingIds = pairing.pairedDevices().map { it.pairingId }.distinct()
                .ifEmpty { listOf(pairing.deviceId) }
        )
        val lan = LanTransport(applicationContext, pairing.deviceId, scope)
        router = TransportRouter(lan, relay, scope)
        // Wire runtime pairing-completion to the relay so a pairing created or
        // accepted after startup (incl. the first pairing on a fresh install)
        // gets its far-path socket immediately, without a process restart.
        pairing.onPairingSubscribed = router::subscribePairing
        clipboardSync = ClipboardSync(applicationContext, pairing, router)
        fileShare = FileShare(applicationContext, pairing, router)
        screenshotCapture = ScreenshotCapture(applicationContext, fileShare)
        pairingHandshake = PairingHandshake(pairing, router)

        router.start()
        dispatchIncoming()
    }

    /**
     * Capture a screenshot using the MediaProjection consent result and send it
     * to all paired devices. On API 29+ MediaProjection may only be acquired
     * while a foreground service of type `mediaProjection` is running, so this
     * elevates the running foreground notification to include that type for the
     * duration of the capture, then restores the data-sync-only type. Returns
     * the PNG byte size, or null on failure.
     */
    suspend fun captureScreenshot(resultCode: Int, data: Intent): Int? {
        elevateToMediaProjection()
        return try {
            screenshotCapture.capture(resultCode, data)
        } finally {
            restoreDataSyncType()
        }
    }

    private fun elevateToMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notification = buildNotification(
                getString(R.string.notif_running_title),
                getString(R.string.notif_running_text)
            )
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
    }

    private fun restoreDataSyncType() {
        // Re-assert the baseline data-sync-only foreground type once capture is
        // done so we don't hold the mediaProjection type longer than needed.
        startForegroundNotification()
    }

    private fun dispatchIncoming() {
        scope.launch {
            router.incoming.collect { envelope ->
                when (envelope.kind) {
                    TransferKind.CLIPBOARD_TEXT -> {
                        val text = clipboardSync.onEnvelope(envelope)
                        if (text != null) notify(getString(R.string.notif_clipboard_title), text)
                    }
                    TransferKind.FILE, TransferKind.PHOTO, TransferKind.SCREENSHOT -> {
                        val item = fileShare.onEnvelope(envelope)
                        if (item != null) notify(getString(R.string.notif_file_title), item.name)
                    }
                    TransferKind.PAIRING_HELLO -> {
                        // The initiator learns the scanner here and records it,
                        // which is what makes the pairing bidirectional.
                        pairingHandshake.onEnvelope(envelope)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        router.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification(
            getString(R.string.notif_running_title),
            getString(R.string.notif_running_text)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            buildNotification(title, text)
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_desc) }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "linkup_sync"
        private const val NOTIF_ID = 1001

        /** Start the foreground sync service. */
        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
