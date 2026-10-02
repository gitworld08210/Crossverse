package com.linkup.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.linkup.app.BuildConfig
import com.linkup.app.R
import com.linkup.app.clipboard.ClipboardSync
import com.linkup.app.pairing.PairingManager
import com.linkup.app.share.FileShare
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundNotification()

        pairing = PairingManager(applicationContext)
        val relay = RelayClient(
            relayUrl = BuildConfig.RELAY_URL,
            deviceId = pairing.deviceId,
            // Phase 1: a device connects under its first pairing. Multi-pairing
            // fan-out across sockets is a later-phase concern.
            pairingId = pairing.pairedDevices().firstOrNull()?.pairingId ?: pairing.deviceId
        )
        val lan = LanTransport(applicationContext, pairing.deviceId, scope)
        router = TransportRouter(lan, relay, scope)
        clipboardSync = ClipboardSync(applicationContext, pairing, router)
        fileShare = FileShare(applicationContext, pairing, router)

        router.start()
        dispatchIncoming()
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
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

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
