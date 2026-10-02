package com.linkup.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption primitives for Linkup.
 *
 * This uses ONLY `javax.crypto` / standard JDK APIs that are present on Android
 * API 26+, so the exact same code runs on the user's phone, tablet and laptop
 * and inside the JVM unit tests. There are NO third-party crypto libraries and
 * NO Android imports.
 *
 * The relay never sees any of this key material: per-link keys are derived
 * locally from the pairing secret, so the cloud only routes opaque ciphertext.
 * That is what makes "works when the phone is far away" safe: remote traffic is
 * end-to-end encrypted just like local traffic.
 */
object E2E {

    private const val AES = "AES"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val HMAC = "HmacSHA256"
    private const val KEY_SIZE_BYTES = 32 // AES-256
    private const val NONCE_SIZE_BYTES = 12 // 96-bit GCM nonce
    private const val GCM_TAG_BITS = 128
    private const val HASH_LEN = 32 // SHA-256 output size

    private val secureRandom = SecureRandom()

    /** A GCM seal result: a fresh random [nonce] and the [ciphertext] (incl. tag). */
    data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sealed) return false
            return nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int =
            31 * nonce.contentHashCode() + ciphertext.contentHashCode()
    }

    /**
     * Derive a 256-bit per-link symmetric key from the shared [pairingSecret]
     * using HKDF-SHA256 (RFC 5869) with the given [context] as the `info`
     * parameter. Different [context] strings yield independent keys from the
     * same secret, so distinct link purposes never share a key.
     */
    fun deriveLinkKey(pairingSecret: ByteArray, context: String): SecretKey {
        val salt = ByteArray(HASH_LEN) // all-zero salt per RFC 5869 when none given
        val prk = hkdfExtract(salt, pairingSecret)
        val okm = hkdfExpand(prk, context.toByteArray(Charsets.UTF_8), KEY_SIZE_BYTES)
        return SecretKeySpec(okm, AES)
    }

    /** HKDF-Extract: PRK = HMAC-SHA256(salt, ikm). */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt, HMAC))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand: produce [length] bytes of output key material. */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(prk, HMAC))
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val toCopy = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    /**
     * Encrypt [plaintext] with AES-256-GCM under [key], using a fresh random
     * 12-byte nonce. Returns the nonce and the ciphertext (which includes the
     * GCM authentication tag).
     */
    fun seal(key: SecretKey, plaintext: ByteArray): Sealed {
        val nonce = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(nonce)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return Sealed(nonce, ciphertext)
    }

    /**
     * Decrypt [ciphertext] with AES-256-GCM under [key] and [nonce]. Throws
     * [javax.crypto.AEADBadTagException] if the key is wrong or the data was
     * tampered with, giving the tamper/isolation guarantee.
     */
    fun open(key: SecretKey, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}
