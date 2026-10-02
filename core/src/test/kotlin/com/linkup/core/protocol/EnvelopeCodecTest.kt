package com.linkup.core.protocol

import com.linkup.core.crypto.E2E
import com.linkup.core.model.DeviceInfo
import com.linkup.core.model.DeviceType
import com.linkup.core.model.TransferKind
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvelopeCodecTest {

    private val secret = ByteArray(32) { (it * 7).toByte() }
    private val linkKey = E2E.deriveLinkKey(secret, "clipboard")

    @Test
    fun `round-trips a ClipboardPayload through seal and open`() {
        val payload = ClipboardPayload("https://example.com/shared-link")
        val envelope = EnvelopeCodec.sealEnvelope(
            linkKey = linkKey,
            from = "phone-1",
            to = "tablet-2",
            pairingId = "pair-abc",
            kind = TransferKind.CLIPBOARD_TEXT,
            plaintextJson = payload.toJson()
        )

        assertEquals("phone-1", envelope.fromDeviceId)
        assertEquals("tablet-2", envelope.toDeviceId)
        assertEquals(TransferKind.CLIPBOARD_TEXT, envelope.kind)

        val opened = EnvelopeCodec.openEnvelope(linkKey, envelope)
        val decoded = ClipboardPayload.fromJson(String(opened, Charsets.UTF_8))
        assertEquals(payload, decoded)
    }

    @Test
    fun `envelope JSON is identical for LAN framing and relay transport`() {
        // One protocol for both transports: the serialized bytes a LAN socket
        // would frame are the same bytes the relay forwards as a text frame.
        val envelope = EnvelopeCodec.sealEnvelope(
            linkKey = linkKey,
            from = "phone-1",
            to = "laptop-3",
            pairingId = "pair-xyz",
            kind = TransferKind.CLIPBOARD_TEXT,
            plaintextJson = ClipboardPayload("far away copy").toJson()
        )
        val json = envelope.toJson()
        // Re-parsing the exact bytes yields an identical envelope.
        assertEquals(envelope, Envelope.fromJson(json))
    }

    @Test
    fun `round-trips a PairingHelloPayload so the initiator learns its peer`() {
        // The scanner sends its own DeviceInfo back to the initiator sealed under
        // the shared link key; the initiator opens it and records the peer.
        val scanner = DeviceInfo(
            id = "tablet-2",
            displayName = "Galaxy Tab",
            deviceType = DeviceType.TABLET
        )
        val envelope = EnvelopeCodec.sealEnvelope(
            linkKey = linkKey,
            from = "tablet-2",
            to = "phone-1",
            pairingId = "pair-abc",
            kind = TransferKind.PAIRING_HELLO,
            plaintextJson = PairingHelloPayload(scanner).toJson()
        )
        assertEquals(TransferKind.PAIRING_HELLO, envelope.kind)

        val opened = EnvelopeCodec.openEnvelope(linkKey, envelope)
        val decoded = PairingHelloPayload.fromJson(String(opened, Charsets.UTF_8))
        assertEquals(scanner, decoded.device)
    }

    @Test
    fun `opening with a different link key fails`() {
        val envelope = EnvelopeCodec.sealEnvelope(
            linkKey = linkKey,
            from = "phone-1",
            to = "tablet-2",
            pairingId = "pair-abc",
            kind = TransferKind.CLIPBOARD_TEXT,
            plaintextJson = ClipboardPayload("secret").toJson()
        )
        val wrongKey = E2E.deriveLinkKey(secret, "different-context")
        assertFailsWith<AEADBadTagException> {
            EnvelopeCodec.openEnvelope(wrongKey, envelope)
        }
    }
}
