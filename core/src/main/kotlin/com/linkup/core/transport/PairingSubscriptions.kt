package com.linkup.core.transport

/**
 * Pure-JVM bookkeeping for which pairing ids a relay client currently holds a
 * socket for.
 *
 * The relay authenticates each WebSocket to a single `(deviceId, pairingId)`, so
 * a device in several pairings needs one socket per pairing. The set of pairings
 * is NOT fixed at startup: a fresh install begins with zero pairings and gains
 * its first one only after the user pairs, and later pairings (phone↔laptop
 * after phone↔tablet) arrive while the process is already running. Each new
 * pairing must get its own socket WITHOUT a restart, or the far (relay) path --
 * the "works when the phone is far away" case, where there is no LAN peer to
 * mask the gap -- silently never comes up for it.
 *
 * This class is the testable core of that logic: it tracks the subscribed set
 * and answers, idempotently, whether a given pairing id is NEW (so the caller
 * should launch a connect loop) or already covered (so the caller does nothing).
 * It is deliberately Android-free so the add/dedup behaviour can be unit tested
 * on the JVM; the Android [RelayClient] delegates its set management here and
 * only owns the coroutine/socket side effects.
 *
 * Thread-safe: all mutations are guarded by an internal lock, so it is safe to
 * call [add] from arbitrary pairing-completion callbacks.
 */
class PairingSubscriptions(initial: Collection<String> = emptyList()) {

    private val lock = Any()
    private val ids = LinkedHashSet<String>()

    init {
        for (id in initial) {
            if (id.isNotBlank()) ids.add(id)
        }
    }

    /**
     * Register [pairingId] as subscribed. Returns true if it was NOT previously
     * subscribed (the caller should start a new connect loop for it) and false
     * if it was already present (idempotent no-op) or blank. Safe to call
     * repeatedly from `createPairing` / `acceptPairing` / `recordPeer`.
     */
    fun add(pairingId: String): Boolean {
        if (pairingId.isBlank()) return false
        return synchronized(lock) { ids.add(pairingId) }
    }

    /** True if [pairingId] is currently subscribed. */
    fun contains(pairingId: String): Boolean =
        synchronized(lock) { ids.contains(pairingId) }

    /** Snapshot of the currently-subscribed pairing ids, in insertion order. */
    fun snapshot(): List<String> =
        synchronized(lock) { ids.toList() }

    /** Number of distinct subscribed pairing ids. */
    val size: Int
        get() = synchronized(lock) { ids.size }
}
