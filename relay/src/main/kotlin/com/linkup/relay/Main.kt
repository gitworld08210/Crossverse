package com.linkup.relay

import com.linkup.core.Linkup

/**
 * Entry point for the Linkup relay server.
 *
 * The relay routes end-to-end-encrypted envelopes between paired devices that
 * cannot reach each other directly (e.g. the phone is on mobile data / far from
 * the other devices). It authenticates by pairing/device IDs but can never
 * decrypt payloads.
 *
 * The actual Ktor WebSocket routing is implemented in a later feature; for the
 * Phase 1 scaffold this just confirms the module configures, compiles, and can
 * reference :core.
 */
fun main() {
    println("Linkup relay ${Linkup.VERSION} (protocol v${Linkup.PROTOCOL_VERSION}) - routing not yet implemented")
}
