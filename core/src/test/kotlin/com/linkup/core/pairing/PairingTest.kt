package com.linkup.core.pairing

import com.linkup.core.model.DeviceInfo
import com.linkup.core.model.DeviceType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingTest {

    private val initiator = DeviceInfo(
        id = "phone-1",
        displayName = "My Phone",
        deviceType = DeviceType.PHONE,
        publicKeyFingerprint = "AB:CD:EF"
    )

    @Test
    fun `generated secret is 32 bytes`() {
        assertEquals(32, Pairing.generateSecret().size)
    }

    @Test
    fun `short code is 8 characters`() {
        assertEquals(8, Pairing.generateShortCode().length)
    }

    @Test
    fun `pairing payload round-trips through encode and parse`() {
        val secret = Pairing.generateSecret()
        val payload = Pairing.createPairingPayload("pair-1", initiator, secret)
        val json = Pairing.encodePairingPayload(payload)
        val parsed = Pairing.parsePairingPayload(json)

        assertEquals("pair-1", parsed.pairingId)
        assertEquals(initiator, parsed.initiator)
        assertEquals(payload, parsed)
    }

    @Test
    fun `secret survives encode and parse intact`() {
        val secret = Pairing.generateSecret()
        val payload = Pairing.createPairingPayload("pair-2", initiator, secret)
        val json = Pairing.encodePairingPayload(payload)
        val parsed = Pairing.parsePairingPayload(json)

        assertContentEquals(secret, parsed.secret())
    }

    @Test
    fun `generated short codes vary`() {
        val codes = (1..20).map { Pairing.generateShortCode() }.toSet()
        // Astronomically unlikely to collide across 20 draws of 32^8 space.
        assertTrue(codes.size > 1)
    }
}
