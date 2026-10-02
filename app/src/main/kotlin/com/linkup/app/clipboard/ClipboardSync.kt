package com.linkup.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.linkup.app.pairing.PairingManager
import com.linkup.app.transport.TransportRouter
import com.linkup.core.model.PairedDevice
import com.linkup.core.model.TransferKind
import com.linkup.core.protocol.ClipboardPayload
import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.EnvelopeCodec

/**
 * Universal Clipboard.
 *
 * IMPORTANT Android limitation: since Android 10 (API 29) an app can only read
 * the system clipboard while it is the FOREGROUND app (or the default IME).
 * Background clipboard reads return null. Linkup therefore syncs the clipboard
 * on an EXPLICIT user action ([sendCurrentClipboard], triggered by the "Send
 * current clipboard" button) rather than silently in the background. Receiving
 * is unaffected: an incoming CLIPBOARD_TEXT envelope is placed on the local
 * clipboard and surfaced via a notification.
 */
class ClipboardSync(
    private val context: Context,
    private val pairing: PairingManager,
    private val router: TransportRouter
) {

    private val clipboard: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Volatile
    var lastReceivedText: String? = null
        private set

    /**
     * Read the current clipboard text (requires the app to be in the foreground,
     * see the class note) and send it as a sealed CLIPBOARD_TEXT envelope to all
     * paired devices. Returns the text that was sent, or null if the clipboard
     * had no text / no pairings exist.
     */
    suspend fun sendCurrentClipboard(): String? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
        if (text.isEmpty()) return null

        val devices = pairing.pairedDevices()
        if (devices.isEmpty()) return null

        for (paired in devices) {
            val envelope = sealClipboard(paired, text) ?: continue
            router.send(envelope)
        }
        return text
    }

    /**
     * Handle an incoming CLIPBOARD_TEXT [envelope]: open it with the per-link key
     * and place the text on the local clipboard. Returns the received text (so
     * the caller can show a notification / update the UI) or null if it could
     * not be opened (unknown pairing / wrong key / tampered).
     */
    fun onEnvelope(envelope: Envelope): String? {
        if (envelope.kind != TransferKind.CLIPBOARD_TEXT) return null
        val key = pairing.linkKey(envelope.pairingId) ?: return null
        val plaintext = runCatching { EnvelopeCodec.openEnvelope(key, envelope) }.getOrNull()
            ?: return null
        val payload = runCatching {
            ClipboardPayload.fromJson(String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return null

        clipboard.setPrimaryClip(ClipData.newPlainText("Linkup", payload.text))
        lastReceivedText = payload.text
        return payload.text
    }

    private fun sealClipboard(paired: PairedDevice, text: String): Envelope? {
        val key = pairing.linkKey(paired.pairingId) ?: return null
        val payload = ClipboardPayload(text)
        return EnvelopeCodec.sealEnvelope(
            linkKey = key,
            from = pairing.deviceId,
            to = paired.device.id,
            pairingId = paired.pairingId,
            kind = TransferKind.CLIPBOARD_TEXT,
            plaintextJson = payload.toJson()
        )
    }
}
