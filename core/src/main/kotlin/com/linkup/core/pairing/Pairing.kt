package com.linkup.core.pairing

import com.linkup.core.model.DeviceInfo
import com.linkup.core.protocol.WireJson
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * The payload carried by a pairing QR code (and, logically, by the pending
 * pairing a short code points to).
 *
 * It bundles the [pairingId], the [initiator] device's public [DeviceInfo], and
 * the shared pairing secret as base64 ([secretB64]). Both devices derive their
 * per-link keys from this secret via
 * [com.linkup.core.crypto.E2E.deriveLinkKey], so after pairing they can protect
 * traffic end-to-end on BOTH the LAN and the relay path.
 */
@Serializable
data class PairingPayload(
    val pairingId: String,
    val initiator: DeviceInfo,
    val secretB64: String
) {
    /** The raw 32-byte pairing secret decoded from [secretB64]. */
    fun secret(): ByteArray = Base64.getDecoder().decode(secretB64)
}

/**
 * Pairing helpers: generate the shared secret, build the QR/short-code payload,
 * and parse it back.
 *
 * Trust model documented inline: the QR code carries the FULL secret (scanning
 * requires physical proximity, so it is safe to embed). The 8-character short
 * code does NOT carry the secret; it is a human-typable handle that indexes a
 * pending pairing held in memory on the initiator and/or on the relay, which
 * then hands the full [PairingPayload] to the device that presents the code.
 */
object Pairing {

    private const val SECRET_SIZE_BYTES = 32
    private const val SHORT_CODE_LEN = 8

    // Crockford-style base32 without easily-confused characters (no I, L, O, U).
    private const val SHORT_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val secureRandom = SecureRandom()

    /** Generate a fresh 32-byte random pairing secret. */
    fun generateSecret(): ByteArray =
        ByteArray(SECRET_SIZE_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * Generate an 8-character human-typable short code. This is a reference to a
     * pending pairing, NOT the secret itself (see the class trust-model note).
     */
    fun generateShortCode(): String {
        val sb = StringBuilder(SHORT_CODE_LEN)
        repeat(SHORT_CODE_LEN) {
            sb.append(SHORT_CODE_ALPHABET[secureRandom.nextInt(SHORT_CODE_ALPHABET.length)])
        }
        return sb.toString()
    }

    /** Build a [PairingPayload] from the parts, base64-encoding the [secret]. */
    fun createPairingPayload(
        pairingId: String,
        initiator: DeviceInfo,
        secret: ByteArray
    ): PairingPayload = PairingPayload(
        pairingId = pairingId,
        initiator = initiator,
        secretB64 = Base64.getEncoder().encodeToString(secret)
    )

    /** Encode a [PairingPayload] as compact JSON suitable for a QR code. */
    fun encodePairingPayload(payload: PairingPayload): String =
        WireJson.json.encodeToString(PairingPayload.serializer(), payload)

    /** Parse the compact JSON produced by [encodePairingPayload]. */
    fun parsePairingPayload(text: String): PairingPayload =
        WireJson.json.decodeFromString(PairingPayload.serializer(), text)
}
