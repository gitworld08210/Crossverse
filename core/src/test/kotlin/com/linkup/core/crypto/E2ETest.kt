package com.linkup.core.crypto

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E2ETest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `seal then open round-trips the plaintext`() {
        val key = E2E.deriveLinkKey(secret, "clipboard")
        val plaintext = "paste me on every device".toByteArray()
        val sealed = E2E.seal(key, plaintext)
        val opened = E2E.open(key, sealed.nonce, sealed.ciphertext)
        assertContentEquals(plaintext, opened)
    }

    @Test
    fun `nonce is 12 bytes`() {
        val key = E2E.deriveLinkKey(secret, "clipboard")
        val sealed = E2E.seal(key, "x".toByteArray())
        assertTrue(sealed.nonce.size == 12)
    }

    @Test
    fun `opening with the wrong key fails with AEADBadTagException`() {
        val good = E2E.deriveLinkKey(secret, "clipboard")
        val wrong = E2E.deriveLinkKey(ByteArray(32) { (it + 1).toByte() }, "clipboard")
        val sealed = E2E.seal(good, "secret".toByteArray())
        assertFailsWith<AEADBadTagException> {
            E2E.open(wrong, sealed.nonce, sealed.ciphertext)
        }
    }

    @Test
    fun `HKDF is deterministic for the same secret and context`() {
        val a = E2E.deriveLinkKey(secret, "clipboard")
        val b = E2E.deriveLinkKey(secret, "clipboard")
        assertContentEquals(a.encoded, b.encoded)
    }

    @Test
    fun `different context yields a different key`() {
        val a = E2E.deriveLinkKey(secret, "clipboard")
        val b = E2E.deriveLinkKey(secret, "file-transfer")
        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    @Test
    fun `derived key is 256 bits`() {
        val key = E2E.deriveLinkKey(secret, "clipboard")
        assertTrue(key.encoded.size == 32)
    }
}
