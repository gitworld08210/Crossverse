package com.linkup.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Entry point for the Linkup relay server.
 *
 * The relay routes end-to-end-encrypted envelopes between paired devices that
 * cannot reach each other directly (e.g. the phone is on mobile data / far from
 * the other devices). It authenticates by pairing/device IDs but can never
 * decrypt payloads.
 *
 * It listens on the port given by the `PORT` environment variable, defaulting
 * to 8080, and serves the HTTP + WebSocket routes defined in [module].
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}

/** Default listen port when `PORT` is unset. */
const val DEFAULT_PORT: Int = 8080
