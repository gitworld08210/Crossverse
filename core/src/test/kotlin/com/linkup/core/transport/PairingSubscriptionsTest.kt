package com.linkup.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the relay subscription-set bookkeeping.
 *
 * This is the unit the v2 review called out as missing: the far-path (relay)
 * socket set must be able to GROW at runtime, because a fresh install starts
 * with zero pairings and the user's first pairing arrives only after the
 * process is already running. A startup-only snapshot (the bug) would leave
 * that first pairing with no relay socket until a restart -- exactly the
 * "phone is far away / no LAN" case where the relay is the only path.
 */
class PairingSubscriptionsTest {

    @Test
    fun `fresh install starts empty`() {
        val subs = PairingSubscriptions()
        assertEquals(0, subs.size)
        assertEquals(emptyList(), subs.snapshot())
    }

    @Test
    fun `first pairing after startup is reported as new`() {
        // Simulates the fresh-install case: construct with no pairings, then the
        // user pairs. add() must return true so the client launches a loop.
        val subs = PairingSubscriptions()
        assertTrue(subs.add("pair-first"))
        assertTrue(subs.contains("pair-first"))
        assertEquals(1, subs.size)
    }

    @Test
    fun `adding is idempotent and never duplicates a loop`() {
        val subs = PairingSubscriptions()
        assertTrue(subs.add("pair-1"))
        // Re-adding the same pairing (e.g. createPairing then a later recordPeer
        // for the same pairing) must NOT request a second connect loop.
        assertFalse(subs.add("pair-1"))
        assertEquals(1, subs.size)
        assertEquals(listOf("pair-1"), subs.snapshot())
    }

    @Test
    fun `second distinct pairing added later is new`() {
        // phone<->tablet first, then phone<->laptop while running.
        val subs = PairingSubscriptions(listOf("pair-tablet"))
        assertFalse(subs.add("pair-tablet")) // already present from construction
        assertTrue(subs.add("pair-laptop"))
        assertEquals(2, subs.size)
        assertTrue(subs.contains("pair-tablet"))
        assertTrue(subs.contains("pair-laptop"))
    }

    @Test
    fun `construction dedups and ignores blanks`() {
        val subs = PairingSubscriptions(listOf("pair-1", "pair-1", "", "   ", "pair-2"))
        assertEquals(2, subs.size)
        assertEquals(listOf("pair-1", "pair-2"), subs.snapshot())
    }

    @Test
    fun `blank pairing id is never subscribed`() {
        val subs = PairingSubscriptions()
        assertFalse(subs.add(""))
        assertFalse(subs.add("   "))
        assertEquals(0, subs.size)
    }

    @Test
    fun `snapshot preserves insertion order`() {
        val subs = PairingSubscriptions()
        subs.add("c")
        subs.add("a")
        subs.add("b")
        assertEquals(listOf("c", "a", "b"), subs.snapshot())
    }
}
