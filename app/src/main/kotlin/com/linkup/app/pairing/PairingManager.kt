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
 * Two ways to pair, both from :core:
 *  - QR: [buildPairingPayloadJson] produces the full [PairingPayload] JSON to
 *    render as a QR; the scanner passes the decoded text to [acceptPairing].
 *  - Short code: a human-typable handle that indexes a pending pairing; the
 *    initiator/relay hands the full payload to the device presenting the code.
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
        return Pairing.createPairingPayload(pairingId, self, secret)
    }

    /** Encode a [payload] as compact JSON for a QR code (delegates to :core). */
    fun buildPairingPayloadJson(payload: PairingPayload): String =
        Pairing.encodePairingPayload(payload)

    /** Generate an 8-char human-typable short code (delegates to :core). */
    fun generateShortCode(): String = Pairing.generateShortCode()

    /**
     * Accept a scanned/typed pairing: parse the [payloadJson] (QR text or the
     * payload the short code resolved to), persist the secret encrypted, derive
     * nothing yet, and record the peer as a [PairedDevice].
     */
    fun acceptPairing(payloadJson: String): PairedDevice {
        val payload = Pairing.parsePairingPayload(payloadJson)
        persistSecret(payload.pairingId, payload.secret())
        val paired = PairedDevice(pairingId = payload.pairingId, device = payload.initiator)
        persistPairedDevice(paired)
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
