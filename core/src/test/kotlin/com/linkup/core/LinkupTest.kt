package com.linkup.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Placeholder test proving the :core JVM unit-test pipeline works end to end in
 * the sandbox (no Android SDK required). Real protocol/pairing/crypto tests
 * arrive in later features.
 */
class LinkupTest {

    @Test
    fun `version is exposed`() {
        assertEquals("0.1.0", Linkup.VERSION)
    }

    @Test
    fun `protocol version is positive`() {
        assertTrue(Linkup.PROTOCOL_VERSION >= 1)
    }
}
