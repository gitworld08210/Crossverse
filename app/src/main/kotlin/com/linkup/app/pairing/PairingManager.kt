package com.linkup.app.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.linkup.core.crypto.E2E
import com.linkup.core.model.DeviceInfo
import com.linkup.core.model.PairedDevice
import com.linkup.core.pairing.Pairing
import com.linkup.core.pairing.PairingPayload
import com.linkup.core.protocol.WireJson
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Owns pairing state on the device.
 *
 * Pairing secrets are the root of all end-to-end encryption, so they are stored
 * ONLY in [EncryptedSharedPreferences] (AES-256 backed by the Android Keystore).
 * Per-link symmetric keys are NEVER stored: they are derived on demand from the
 * secret via :core [E2E.deriveLinkKey], so a key leak can't happen from storage
 * and the derivation logic stays in the shared, unit-tested core.
 *
 * Pairing in Phase 1 is QR-based: [buildPairingPayloadJson] produces the full
 * [PairingPayload] JSON to render as a QR, and the scanner passes the decoded
 * text (or the same payload pasted manually) to [acceptPairing]. There is no
 * short-code resolution in Phase 1 (no relay-side pending-pairing index exists),
 * so the cosmetic short-code API has been removed.
 */
class PairingManager(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Invoked with a pairing id whenever a pairing is created, accepted, or a
     * new peer is recorded. The transport owner (SyncService / LinkupContainer)
     * sets this to [com.linkup.app.transport.TransportRouter.subscribePairing]
     * so the relay opens a far-path socket for the new pairing immediately,
     * without a process restart. Idempotent on the router side. Null until the
     * router is wired, and safe to leave null (pairings are still persisted and
     * picked up on the next startup snapshot).
     */
    @Volatile
    var onPairingSubscribed: ((String) -> Unit)? = null

    /** Stable, per-install device id used as [Envelope.fromDeviceId]. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /**
     * Create a brand-new pairing as the initiator and return the [PairingPayload]
     * JSON to show as a QR (and the generated short code via [shortCodeFor]).
     * The fresh secret is persisted encrypted immediately so this device can
     * derive link keys once the peer joins.
     */
    fun createPairing(self: DeviceInfo): PairingPayload {
        val pairingId = UUID.randomUUID().toString()
        val secret = Pairing.generateSecret()
        persistSecret(pairingId, secret)
        // Open the far-path relay socket for this pairing right away so a device
        // that pairs while running can sync off-LAN without a restart.
        onPairingSubscribed?.invoke(pairingId)
        return Pairing.createPairingPayload(pairingId, self, secret)
    }

    /** Encode a [payload] as compact JSON for a QR code (delegates to :core). */
    fun buildPairingPayloadJson(payload: PairingPayload): String =
        Pairing.encodePairingPayload(payload)

    /**
     * Accept a scanned/typed pairing: parse the [payloadJson] (QR text or the
     * full payload pasted manually), persist the secret encrypted, derive
     * nothing yet, and record the peer as a [PairedDevice].
     */
    fun acceptPairing(payloadJson: String): PairedDevice {
        val payload = Pairing.parsePairingPayload(payloadJson)
        persistSecret(payload.pairingId, payload.secret())
        val paired = PairedDevice(pairingId = payload.pairingId, device = payload.initiator)
        persistPairedDevice(paired)
        // First pairing on a fresh install reaches the far path here: open its
        // relay socket now so remote (no-LAN) sync works without a restart.
        onPairingSubscribed?.invoke(payload.pairingId)
        return paired
    }

    /**
     * Derive the per-link AES-256 key for [pairingId] from the stored secret.
     * Returns null if the secret is unknown. The [context] string lets distinct
     * link purposes derive independent keys from the same secret.
     */
    fun linkKey(pairingId: String, context: String = DEFAULT_LINK_CONTEXT): SecretKey? {
        val secret = loadSecret(pairingId) ?: return null
        return E2E.deriveLinkKey(secret, context)
    }

    /**
     * Record a peer we learned about AFTER the initial pairing exchange, e.g.
     * the initiator receiving the scanner's [TransferKind.PAIRING_HELLO]. The
     * secret for [pairingId] is already stored (the initiator persisted it in
     * [createPairing]); here we only add the peer so the pairing becomes
     * bidirectional. No-op if we already know this device for this pairing.
     */
    fun recordPeer(pairingId: String, device: DeviceInfo) {
        val already = pairedDevices().any { it.pairingId == pairingId && it.device.id == device.id }
        if (already) return
        persistPairedDevice(PairedDevice(pairingId = pairingId, device = device))
        // A brand-new pairing/peer learned at runtime also needs its far-path
        // socket. Idempotent on the router side if one already exists.
        onPairingSubscribed?.invoke(pairingId)
    }

    /** The local device's public [DeviceInfo] for pairing handshakes. */
    fun selfDeviceInfo(displayName: String, deviceType: com.linkup.core.model.DeviceType): DeviceInfo =
        DeviceInfo(id = deviceId, displayName = displayName, deviceType = deviceType)

    /** All devices this device has paired with. */
    fun pairedDevices(): List<PairedDevice> {
        val raw = prefs.getStringSet(KEY_PAIRED, emptySet()) ?: emptySet()
        return raw.mapNotNull { json ->
            runCatching {
                WireJson.json.decodeFromString(PairedDevice.serializer(), json)
            }.getOrNull()
        }
    }

    // --- internals -------------------------------------------------------

    private fun persistSecret(pairingId: String, secret: ByteArray) {
        prefs.edit()
            .putString(secretKey(pairingId), Base64.getEncoder().encodeToString(secret))
            .apply()
    }

    private fun loadSecret(pairingId: String): ByteArray? {
        val b64 = prefs.getString(secretKey(pairingId), null) ?: return null
        return runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
    }

    private fun persistPairedDevice(paired: PairedDevice) {
        val existing = prefs.getStringSet(KEY_PAIRED, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(WireJson.json.encodeToString(PairedDevice.serializer(), paired))
        prefs.edit().putStringSet(KEY_PAIRED, existing).apply()
    }

    private fun secretKey(pairingId: String) = "$KEY_SECRET_PREFIX$pairingId"

    companion object {
        private const val PREFS_NAME = "linkup_pairing_secure"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRED = "paired_devices"
        private const val KEY_SECRET_PREFIX = "secret_"

        /** HKDF `info` context used to derive the default clipboard/file link key. */
        const val DEFAULT_LINK_CONTEXT = "linkup/link/v1"
    }
}
