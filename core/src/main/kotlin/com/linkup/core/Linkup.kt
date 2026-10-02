package com.linkup.core

/**
 * Top-level metadata for the Linkup shared core.
 *
 * The :core module is pure Kotlin/JVM with no Android dependencies so that the
 * wire protocol, pairing, and end-to-end-encryption logic can be unit-tested on
 * the JVM and reused by both the Android :app and the Ktor :relay server.
 */
object Linkup {
    /** Semantic version of the Linkup shared core / wire protocol baseline. */
    const val VERSION: String = "0.1.0"

    /** Protocol revision; bumped when the on-the-wire envelope format changes. */
    const val PROTOCOL_VERSION: Int = 1
}
