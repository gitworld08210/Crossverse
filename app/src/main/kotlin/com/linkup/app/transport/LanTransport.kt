package com.linkup.app.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.linkup.core.protocol.Envelope
import com.linkup.core.protocol.Framing
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LAN direct transport.
 *
 * This is the NEAR path: when two paired devices are on the same Wi-Fi, we
 * advertise a `_linkup._tcp` service via [NsdManager], discover the peer the
 * same way, open a TCP [Socket], and exchange the SAME sealed [Envelope]s used
 * by the relay — only here they are wrapped in :core length-prefixed [Framing]
 * instead of WebSocket text frames. No cloud, lower latency.
 *
 * The payloads are byte-for-byte identical to the relay path, so switching
 * between LAN and relay never changes what crosses the wire: always an opaque,
 * end-to-end-encrypted envelope.
 */
class LanTransport(
    private val context: Context,
    private val deviceId: String,
    private val scope: CoroutineScope
) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)

    /** Envelopes received over the LAN socket (sealed; open via :core). */
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    // deviceId(service name) -> resolved peer endpoint discovered on the LAN.
    private val discoveredPeers = ConcurrentHashMap<String, NsdServiceInfo>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** True when at least one peer has been discovered + resolved on the LAN. */
    val hasPeer: Boolean get() = discoveredPeers.isNotEmpty()

    /**
     * Start the LAN transport: bind a [ServerSocket] on an ephemeral port,
     * register the `_linkup._tcp` service pointing at it, and start discovery of
     * peers. Idempotent-ish; call once from the service.
     */
    fun start() {
        val server = ServerSocket(0) // ephemeral port chosen by the OS.
        serverSocket = server
        acceptLoop(server)
        registerService(server.localPort)
        startDiscovery()
    }

    /** Accept inbound LAN connections and read framed envelopes from each. */
    private fun acceptLoop(server: ServerSocket) {
        scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                launch(Dispatchers.IO) { readSocket(socket) }
            }
        }
    }

    private suspend fun readSocket(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                while (scope.isActive) {
                    val bytes = Framing.readFrame(input)
                    val envelope = try {
                        Envelope.fromJson(String(bytes, Charsets.UTF_8))
                    } catch (_: Exception) {
                        continue
                    }
                    _incoming.emit(envelope)
                }
            }
        } catch (_: Exception) {
            // Peer closed or stream ended; just drop this socket.
        }
    }

    /** Register the `_linkup._tcp` service so peers can discover this device. */
    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_PREFIX$deviceId"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "LAN service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "LAN registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Discover peers advertising `_linkup._tcp` and resolve their endpoints. */
    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                // Skip our own advertisement.
                if (service.serviceName == "$SERVICE_PREFIX$deviceId") return
                if (service.serviceType.trim('.') != SERVICE_TYPE.trim('.')) return
                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                discoveredPeers.remove(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "LAN discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(service: NsdServiceInfo) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "LAN resolve failed: $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                discoveredPeers[info.serviceName] = info
                Log.i(TAG, "LAN peer resolved: ${info.serviceName} @ ${info.host}:${info.port}")
            }
        })
    }

    /**
     * Send [envelope] to every discovered LAN peer using :core framing. Returns
     * true if it was written to at least one peer, false if no peer is currently
     * reachable on the LAN (caller falls back to the relay).
     */
    suspend fun send(envelope: Envelope): Boolean = withContext(Dispatchers.IO) {
        val peers = discoveredPeers.values.toList()
        if (peers.isEmpty()) return@withContext false
        var delivered = false
        val payload = envelope.toJson().toByteArray(Charsets.UTF_8)
        for (peer in peers) {
            val host: InetAddress = peer.host ?: continue
            try {
                Socket(host, peer.port).use { socket ->
                    Framing.writeFrame(socket.getOutputStream(), payload)
                    delivered = true
                }
            } catch (_: Exception) {
                // Try the next peer; one unreachable socket shouldn't abort all.
            }
        }
        delivered
    }

    /** Stop advertising/discovering and close the server socket. */
    fun stop() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        discoveredPeers.clear()
    }

    companion object {
        private const val TAG = "LanTransport"
        const val SERVICE_TYPE = "_linkup._tcp."
        private const val SERVICE_PREFIX = "linkup-"
    }
}
