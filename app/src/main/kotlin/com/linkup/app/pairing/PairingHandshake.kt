package com.linkup.app.pairing

import com.linkup.app.transport.TransportRouter
import com.linkup.core.model.DeviceInfo
import com.linkup.core.model.PairedDevice
import com.linkup.core.model.TransferKind
import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.EnvelopeCodec
import com.linkup.core.protocol.PairingHelloPayload

/**
 * Completes a pairing so it is BIDIRECTIONAL.
 *
 * The device that scans a QR learns the initiator's [DeviceInfo] from the
 * payload and records it immediately. The initiator, however, only showed a QR
 * and never learned the scanner, so it has an empty peer list and can send
 * nothing back. This helper closes that gap over whichever transport is live
 * (LAN or relay): the scanner seals its own [DeviceInfo] as a
 * [TransferKind.PAIRING_HELLO] and sends it to the initiator; the initiator
 * opens it and records the scanner via [PairingManager.recordPeer].
 *
 * The hello is sealed under the shared per-link key exactly like any other
 * payload, so the relay still never sees who is pairing with whom in plaintext.
 */
class PairingHandshake(
    private val pairing: PairingManager,
    private val router: TransportRouter
) {

    /**
     * Called on the SCANNER right after [PairingManager.acceptPairing]. Sends
     * the scanner's own [DeviceInfo] ([self]) to the [paired] initiator so the
     * initiator can record this device as a peer.
     */
    suspend fun announceToInitiator(self: DeviceInfo, paired: PairedDevice) {
        val key = pairing.linkKey(paired.pairingId) ?: return
        val envelope = EnvelopeCodec.sealEnvelope(
            linkKey = key,
            from = pairing.deviceId,
            to = paired.device.id,
            pairingId = paired.pairingId,
            kind = TransferKind.PAIRING_HELLO,
            plaintextJson = PairingHelloPayload(self).toJson()
        )
        router.send(envelope)
    }

    /**
     * Called on the INITIATOR when a [TransferKind.PAIRING_HELLO] envelope
     * arrives. Opens it with the shared link key and records the sender as a
     * peer, making the pairing bidirectional. Returns the recorded
     * [DeviceInfo], or null if the envelope was not a valid hello.
     */
    fun onEnvelope(envelope: Envelope): DeviceInfo? {
        if (envelope.kind != TransferKind.PAIRING_HELLO) return null
        val key = pairing.linkKey(envelope.pairingId) ?: return null
        val plaintext = runCatching { EnvelopeCodec.openEnvelope(key, envelope) }.getOrNull()
            ?: return null
        val hello = runCatching {
            PairingHelloPayload.fromJson(String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return null
        pairing.recordPeer(envelope.pairingId, hello.device)
        return hello.device
    }
}
