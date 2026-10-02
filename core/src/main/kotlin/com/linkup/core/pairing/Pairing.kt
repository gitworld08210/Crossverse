package com.linkup.core.pairing

import com.linkup.core.model.DeviceInfo
import com.linkup.core.protocol.WireJson
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * The payload carried by a pairing QR code.
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
 * Pairing helpers: generate the shared secret, build the QR payload, and parse
 * it back.
 *
 * Trust model: the QR code carries the FULL secret. Scanning requires physical
 * proximity, so embedding the secret is safe (the proximity itself is the trust
 * signal). Phase 1 pairs purely by QR (or by pasting the same payload JSON); a
 * short-code handle that would index a relay-held pending pairing is a later
 * phase, so no short-code generator exists here.
 */
object Pairing {

    private const val SECRET_SIZE_BYTES = 32

    private val secureRandom = SecureRandom()

    /** Generate a fresh 32-byte random pairing secret. */
    fun generateSecret(): ByteArray =
        ByteArray(SECRET_SIZE_BYTES).also { secureRandom.nextBytes(it) }

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
