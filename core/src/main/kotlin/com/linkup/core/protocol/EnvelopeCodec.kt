package com.linkup.core.protocol

import com.linkup.core.crypto.E2E
import com.linkup.core.model.TransferKind
import java.util.Base64
import javax.crypto.SecretKey

/**
 * High-level bridge between the crypto layer ([E2E]) and the wire model
 * ([Envelope]). This is the single API that both the Android :app and the
 * :relay tests use, which is what guarantees the LAN path and the relay path
 * speak one identical protocol.
 */
object EnvelopeCodec {

    /**
     * Seal [plaintextJson] under [linkKey] and wrap it in an addressed
     * [Envelope]. The plaintext is encrypted with AES-256-GCM; the resulting
     * nonce and ciphertext are base64-encoded into the envelope so it serializes
     * identically for LAN framing and relay WebSocket text frames.
     */
    fun sealEnvelope(
        linkKey: SecretKey,
        from: String,
        to: String,
        pairingId: String,
        kind: TransferKind,
        plaintextJson: String
    ): Envelope {
        val sealed = E2E.seal(linkKey, plaintextJson.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return Envelope(
            fromDeviceId = from,
            toDeviceId = to,
            pairingId = pairingId,
            kind = kind,
            nonceB64 = encoder.encodeToString(sealed.nonce),
            ciphertextB64 = encoder.encodeToString(sealed.ciphertext)
        )
    }

    /**
     * Open an [envelope] previously produced by [sealEnvelope] (by either
     * transport) using [linkKey], returning the decrypted plaintext bytes.
     * Throws [javax.crypto.AEADBadTagException] if [linkKey] is wrong or the
     * envelope was tampered with.
     */
    fun openEnvelope(linkKey: SecretKey, envelope: Envelope): ByteArray {
        val decoder = Base64.getDecoder()
        val nonce = decoder.decode(envelope.nonceB64)
        val ciphertext = decoder.decode(envelope.ciphertextB64)
        return E2E.open(linkKey, nonce, ciphertext)
    }
}
