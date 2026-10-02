package com.linkup.relay

import com.linkup.core.protocol.Envelope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory, thread-safe registry of connected device sessions and the router
 * that forwards sealed [Envelope]s between the devices of a pairing.
 *
 * The hub is deliberately ZERO-KNOWLEDGE: it only ever moves opaque, already
 * end-to-end-encrypted envelopes around. It has no link key and never calls any
 * decrypt/open API, so the relay operator cannot read user data even though it
 * handles every byte that flows between a phone that is far away and its
 * tablet/laptop.
 *
 * Sessions are keyed `pairingId -> (deviceId -> [DeviceSession])`. When an
 * envelope is addressed to a device that currently has no live session (the
 * remote phone is briefly asleep / out of coverage), it is parked in a bounded,
 * TTL-limited per-device offline queue and flushed the next time that device
 * connects. That is what lets sync survive a device dropping offline for a
 * moment without losing the message.
 */
class RelayHub(
    private val offlineQueueMaxPerDevice: Int = DEFAULT_OFFLINE_QUEUE_MAX,
    private val offlineTtlMillis: Long = DEFAULT_OFFLINE_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** A single connected device able to receive forwarded envelopes. */
    fun interface DeviceSession {
        /** Deliver one sealed envelope to this device over its transport. */
        suspend fun send(envelope: Envelope)
    }

    private data class QueuedEnvelope(val envelope: Envelope, val enqueuedAt: Long)

    // pairingId -> (deviceId -> live session). Both maps are concurrent so reads
    // during routing never need the mutex; the mutex only guards the compound
    // register / unregister / queue-flush operations so they stay consistent.
    private val sessions =
        ConcurrentHashMap<String, ConcurrentHashMap<String, DeviceSession>>()

    // pairingId -> (deviceId -> parked envelopes for a device that is offline).
    private val offlineQueues =
        ConcurrentHashMap<String, ConcurrentHashMap<String, ArrayDeque<QueuedEnvelope>>>()

    private val mutex = Mutex()

    /**
     * Register [session] for ([pairingId], [deviceId]) and return any envelopes
     * that were queued while the device was offline (already pruned of expired
     * entries). The caller is expected to deliver the returned list to flush the
     * backlog, so a reconnecting remote device immediately catches up.
     */
    suspend fun register(
        pairingId: String,
        deviceId: String,
        session: DeviceSession
    ): List<Envelope> = mutex.withLock {
        sessions.getOrPut(pairingId) { ConcurrentHashMap() }[deviceId] = session

        val queued = offlineQueues[pairingId]?.remove(deviceId) ?: return@withLock emptyList()
        val now = clock()
        queued
            .filter { now - it.enqueuedAt <= offlineTtlMillis }
            .map { it.envelope }
    }

    /** Remove the session for ([pairingId], [deviceId]) on close/error. */
    suspend fun unregister(pairingId: String, deviceId: String) = mutex.withLock {
        val devices = sessions[pairingId] ?: return@withLock
        devices.remove(deviceId)
        if (devices.isEmpty()) {
            sessions.remove(pairingId)
        }
    }

    /**
     * Forward [envelope] to every OTHER device in its pairing. For a device with
     * a live session the envelope is delivered immediately; for one that is
     * offline it is parked in that device's bounded offline queue to be flushed
     * on reconnect.
     *
     * Routing is purely by the cleartext [Envelope.pairingId] /
     * [Envelope.fromDeviceId] metadata; the ciphertext is never inspected.
     */
    suspend fun routeEnvelope(envelope: Envelope) {
        val pairingId = envelope.pairingId
        val sender = envelope.fromDeviceId

        val liveDevices = sessions[pairingId]
        // Deliver to all currently-connected peers (everyone but the sender).
        liveDevices?.forEach { (deviceId, session) ->
            if (deviceId != sender) {
                session.send(envelope)
            }
        }

        // If the envelope is addressed to a specific peer that is NOT connected,
        // park it so the device gets it when it comes back online.
        val target = envelope.toDeviceId
        if (target.isNotEmpty() && target != sender) {
            val targetLive = liveDevices?.containsKey(target) == true
            if (!targetLive) {
                enqueueOffline(pairingId, target, envelope)
            }
        }
    }

    private suspend fun enqueueOffline(
        pairingId: String,
        deviceId: String,
        envelope: Envelope
    ) = mutex.withLock {
        val queue = offlineQueues
            .getOrPut(pairingId) { ConcurrentHashMap() }
            .getOrPut(deviceId) { ArrayDeque() }

        val now = clock()
        // Drop entries that have outlived their TTL before enforcing the bound.
        while (queue.isNotEmpty() && now - queue.first().enqueuedAt > offlineTtlMillis) {
            queue.removeFirst()
        }
        // Enforce the max size by evicting the oldest parked envelope.
        while (queue.size >= offlineQueueMaxPerDevice) {
            queue.removeFirst()
        }
        queue.addLast(QueuedEnvelope(envelope, now))
    }

    companion object {
        /** Default cap on parked envelopes per offline device. */
        const val DEFAULT_OFFLINE_QUEUE_MAX: Int = 100

        /** Default time a parked envelope is kept before it expires (5 minutes). */
        const val DEFAULT_OFFLINE_TTL_MILLIS: Long = 5 * 60 * 1000
    }
}
