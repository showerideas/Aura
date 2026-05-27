package com.showerideas.aura.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi Direct transport implementing [NearbyTransport].
 *
 * Why Wi-Fi Direct?
 * Google Nearby Connections requires Play Services. Replacing it with a pure
 * Android Wi-Fi Direct stack removes the GMS dependency and makes AURA eligible
 * for F-Droid distribution. The [NearbyTransport] abstraction means the entire
 * crypto layer ([WireProtocol], [CryptoUtils], [SasVerifier]) is reused unchanged.
 *
 * Architecture
 * ```
 * ┌───────────────┐         ┌───────────────┐
 * │  Peer A       │ Wi-Fi   │  Peer B       │
 * │  (advertise)  │  P2P    │  (discover)   │
 * │  Group Owner  │◄────────│  Client       │
 * │  TCP server   │  TCP    │  TCP client   │
 * │  port 8988    │────────►│               │
 * └───────────────┘         └───────────────┘
 * ```
 *
 * Service discovery
 * AURA uses Wi-Fi Direct DNS-SD service registration (`_aura._tcp`) so peers
 * can find each other without knowing each other's MAC addresses in advance.
 * Service records include a `localName` TXT record used as the Nearby-compatible
 * "endpoint name".
 *
 * Group Owner (GO) negotiation
 * Wi-Fi Direct requires one peer to be the Group Owner. GO acts as the TCP server;
 * the client connects to the GO's IP address. To break ties deterministically,
 * we set `groupOwnerIntent` based on lexicographic comparison of our announced
 * `localName` against the peer's — lower name → higher intent (more eager to be GO).
 * This mirrors the SAS derivation tiebreaker in [SasVerifier].
 *
 * Data framing
 * Each payload is length-prefixed: `[4-byte big-endian length][payload bytes]`.
 * [DataInputStream.readInt] / [DataOutputStream.writeInt] handle the framing.
 * Maximum payload size: 1 MB (enforced at read time to prevent OOM attacks).
 *
 * Usage
 * ```kotlin
 * val transport = WifiDirectTransport(context)
 * transport.onEndpointFound = { id, name -> transport.requestConnection(localName, id) }
 * transport.onConnected     = { id, name, _ -> /* start handshake */ }
 * transport.onPayloadReceived = { id, bytes -> /* handle message */ }
 * transport.startAdvertising(localName, SERVICE_ID)
 * transport.startDiscovery(SERVICE_ID)
 * // ...
 * transport.release()   // call in onDestroy
 * ```
 *
 * Permissions required
 * - `android.permission.ACCESS_WIFI_STATE`
 * - `android.permission.CHANGE_WIFI_STATE`
 * - `android.permission.ACCESS_FINE_LOCATION` (API < 33)
 * - `android.permission.NEARBY_WIFI_DEVICES` (API 33+)
 * All declared in AndroidManifest.xml.
 */
@SuppressLint("MissingPermission")   // permissions checked at runtime in MainActivity
class WifiDirectTransport(private val context: Context) : NearbyTransport {

    // NearbyTransport callbacks

    override var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)? = null
    override var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)? = null
    override var onDisconnected: ((endpointId: String) -> Unit)? = null
    override var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onConnectionInitiated: ((endpointId: String, remoteName: String) -> Unit)? = null

    // Internal state

    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel =
        manager.initialize(context, context.mainLooper, null)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiverRegistered = AtomicBoolean(false)

    /** Our announced local name — set in [startAdvertising]. */
    @Volatile private var localName: String = ""

    /** MAC address → remoteName for all discovered peers. */
    private val discoveredPeers = ConcurrentHashMap<String, String>()

    /** Active sockets — MAC address → Socket. */
    private val activeSockets = ConcurrentHashMap<String, Socket>()

    /** Active output streams — MAC address → DataOutputStream. */
    private val activeStreams = ConcurrentHashMap<String, DataOutputStream>()

    /** Whether we are the Group Owner for the current connection. */
    @Volatile private var isGroupOwner: Boolean = false

    /** Server socket (only used when we are the Group Owner). */
    @Volatile private var serverSocket: ServerSocket? = null

    /** The peer MAC address for the current single P2P connection. */
    @Volatile private var connectedPeerMac: String? = null

    // BroadcastReceiver

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Legacy peer list — only used when DNS-SD is unavailable
                    manager.requestPeers(channel) { peerList ->
                        for (device in peerList.deviceList) {
                            val mac = device.deviceAddress
                            val name = device.deviceName
                            if (!discoveredPeers.containsKey(mac)) {
                                discoveredPeers[mac] = name
                                Timber.d("WifiDirect: peer found via PEERS_CHANGED $name ($mac)")
                                onEndpointFound?.invoke(mac, name)
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                    }
                    if (networkInfo?.isConnected == true) {
                        manager.requestConnectionInfo(channel) { info -> handleConnectionInfo(info) }
                    } else {
                        handleDisconnect()
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        Timber.w("WifiDirect: Wi-Fi P2P disabled on device")
                    }
                }
            }
        }
    }

    // NearbyTransport — lifecycle

    override fun startAdvertising(localName: String, serviceId: String) {
        this.localName = localName
        registerBroadcastReceiver()
        val record = mapOf("localName" to localName, "serviceId" to serviceId)
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(localName, SERVICE_TYPE, record)
        manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Timber.d("WifiDirect: advertising as $localName") }
                    override fun onFailure(reason: Int) {
                        Timber.e("WifiDirect: addLocalService failed (reason=$reason)")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Timber.e("WifiDirect: clearLocalServices failed (reason=$reason)")
            }
        })
    }

    override fun startDiscovery(serviceId: String) {
        registerBroadcastReceiver()
        // DNS-SD service discovery — finds _aura._tcp services on the local P2P network
        manager.setDnsSdResponseListeners(
            channel,
            { instanceName, registrationType, device ->
                // Service resolved — extract peer info
                val mac = device.deviceAddress
                val name = instanceName.ifBlank { device.deviceName }
                if (!discoveredPeers.containsKey(mac)) {
                    discoveredPeers[mac] = name
                    Timber.i("WifiDirect: DNS-SD found $name ($mac)")
                    onEndpointFound?.invoke(mac, name)
                }
            },
            { fullDomainName, txtRecordMap, device ->
                // TXT record received — merge remoteName from the record
                val mac = device.deviceAddress
                val name = txtRecordMap["localName"] ?: device.deviceName
                discoveredPeers[mac] = name
                Timber.d("WifiDirect: DNS-SD TXT record from $name ($mac)")
            }
        )
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                startDiscoveryWithBusyRetry(attempt = 0)
            }
            override fun onFailure(reason: Int) {
                Timber.e("WifiDirect: addServiceRequest failed (reason=$reason)")
            }
        })
    }

    /**
     * Calls [WifiP2pManager.discoverServices] with a Samsung/MIUI BUSY-error retry loop.
     *
     * On some OEM ROMs (Samsung One UI, Xiaomi MIUI) `discoverServices` returns
     * [WifiP2pManager.BUSY] (reason=2) if called too soon after a previous discovery
     * session. We retry up to [DISCOVERY_BUSY_MAX_RETRIES] times with a fixed
     * [DISCOVERY_BUSY_RETRY_DELAY_MS] delay before falling back to plain peer
     * discovery so the app keeps working even if DNS-SD is unavailable.
     */
    private fun startDiscoveryWithBusyRetry(attempt: Int) {
        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WifiDirect: service discovery started (attempt=${attempt + 1})")
            }
            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && attempt < DISCOVERY_BUSY_MAX_RETRIES) {
                    Timber.w(
                        "WifiDirect: discoverServices BUSY (attempt=${attempt + 1}/$DISCOVERY_BUSY_MAX_RETRIES)" +
                        " — retrying in ${DISCOVERY_BUSY_RETRY_DELAY_MS}ms"
                    )
                    scope.launch {
                        kotlinx.coroutines.delay(DISCOVERY_BUSY_RETRY_DELAY_MS)
                        startDiscoveryWithBusyRetry(attempt + 1)
                    }
                } else {
                    Timber.w(
                        "WifiDirect: discoverServices failed (reason=$reason) — falling back to peer discovery"
                    )
                    // Fallback: plain peer discovery (no DNS-SD)
                    manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { Timber.d("WifiDirect: fallback peer discovery started") }
                        override fun onFailure(r: Int) { Timber.e("WifiDirect: discoverPeers also failed (reason=$r)") }
                    })
                }
            }
        })
    }

    override fun requestConnection(localName: String, endpointId: String) {
        val remoteName = discoveredPeers[endpointId] ?: endpointId
        // Group Owner intent: lower localName lexicographically → higher intent (prefer to be GO)
        val goIntent = if (localName <= remoteName) GROUP_OWNER_INTENT_HIGH else GROUP_OWNER_INTENT_LOW
        val config = WifiP2pConfig().apply {
            deviceAddress = endpointId
            groupOwnerIntent = goIntent
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WifiDirect: connection request sent to $endpointId (goIntent=$goIntent)")
            }
            override fun onFailure(reason: Int) {
                Timber.e("WifiDirect: connect failed (reason=$reason)")
            }
        })
    }

    /**
     * Wi-Fi Direct doesn't have an explicit "accept" step — the OS handles
     * connection acceptance. This is a no-op that satisfies the interface contract.
     */
    override fun acceptConnection(endpointId: String) {
        Timber.d("WifiDirect: acceptConnection called for $endpointId (no-op — OS handles Wi-Fi P2P acceptance)")
    }

    /**
     * Disconnect from [endpointId] and remove the group.
     * Wi-Fi Direct doesn't support rejecting individual peers from a group;
     * the entire group must be dissolved.
     */
    override fun rejectConnection(endpointId: String) {
        Timber.w("WifiDirect: rejectConnection $endpointId — removing group")
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Timber.d("WifiDirect: group removed (rejection)") }
            override fun onFailure(reason: Int) { Timber.e("WifiDirect: removeGroup failed (reason=$reason)") }
        })
    }

    override fun stopAdvertising() {
        manager.clearLocalServices(channel, loggingListener("clearLocalServices"))
        Timber.d("WifiDirect: stopped advertising")
    }

    override fun stopDiscovery() {
        manager.stopPeerDiscovery(channel, loggingListener("stopPeerDiscovery"))
        manager.clearServiceRequests(channel, loggingListener("clearServiceRequests"))
        Timber.d("WifiDirect: stopped discovery")
    }

    override fun stopAllEndpoints() {
        closeAllSockets()
        serverSocket?.runCatching { close() }
        serverSocket = null
        manager.removeGroup(channel, loggingListener("removeGroup"))
        unregisterBroadcastReceiver()
        discoveredPeers.clear()
        connectedPeerMac = null
        Timber.d("WifiDirect: all endpoints stopped")
    }

    // NearbyTransport — data transfer

    override fun sendBytes(endpointId: String, data: ByteArray) {
        val out = activeStreams[endpointId]
        if (out == null) {
            Timber.e("WifiDirect: no active stream to $endpointId — sendBytes dropped")
            return
        }
        scope.launch {
            try {
                synchronized(out) {
                    out.writeInt(data.size)
                    out.write(data)
                    out.flush()
                }
                Timber.d("WifiDirect: sent ${data.size} bytes to $endpointId")
            } catch (e: IOException) {
                Timber.e(e, "WifiDirect: sendBytes failed to $endpointId")
                handlePeerDisconnect(endpointId)
            }
        }
    }

    // Resource release

    /**
     * Release all Wi-Fi Direct resources. Call this from [Service.onDestroy].
     * After this call, the instance must not be used further.
     */
    fun release() {
        stopAllEndpoints()
        scope.cancel()
        // WifiP2pManager.Channel.close() requires API 27; guard to keep minSdk 26 clean.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            channel.close()
        }
    }

    // Connection handling

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        val peerMac = connectedPeerMac ?: discoveredPeers.keys.firstOrNull() ?: "unknown"
        Timber.i("WifiDirect: connected — isGroupOwner=$isGroupOwner peerMac=$peerMac")

        if (isGroupOwner) {
            // We are the GO — start a TCP server and wait for the client to connect
            scope.launch { startTcpServer(peerMac) }
        } else {
            // We are the client — connect to the GO's group owner address
            val goAddress = info.groupOwnerAddress?.hostAddress
            if (goAddress == null) {
                Timber.e("WifiDirect: group owner address is null — cannot connect")
                return
            }
            scope.launch { connectTcpClient(peerMac, goAddress) }
        }
    }

    private suspend fun startTcpServer(peerMac: String) {
        try {
            val ss = ServerSocket(DATA_PORT).also {
                it.soTimeout = SERVER_ACCEPT_TIMEOUT_MS
                serverSocket = it
            }
            Timber.d("WifiDirect: TCP server listening on port $DATA_PORT (accept timeout=${SERVER_ACCEPT_TIMEOUT_MS}ms)")
            val socket = ss.accept().also { it.soTimeout = READ_TIMEOUT_MS }
            val remoteName = discoveredPeers[peerMac] ?: peerMac
            onPeerSocketReady(peerMac, remoteName, socket, isIncoming = true)
        } catch (e: IOException) {
            Timber.e(e, "WifiDirect: TCP server error")
        }
    }

    private suspend fun connectTcpClient(peerMac: String, goAddress: String) {
        // Retry a few times — the GO may not have its server socket ready yet
        repeat(MAX_CONNECT_RETRIES) { attempt ->
            try {
                val socket = Socket(goAddress, DATA_PORT).also { it.soTimeout = READ_TIMEOUT_MS }
                val remoteName = discoveredPeers[peerMac] ?: peerMac
                onPeerSocketReady(peerMac, remoteName, socket, isIncoming = false)
                return
            } catch (e: IOException) {
                Timber.w("WifiDirect: TCP connect attempt ${attempt + 1}/$MAX_CONNECT_RETRIES failed — retrying")
                kotlinx.coroutines.delay(CONNECT_RETRY_DELAY_MS)
            }
        }
        Timber.e("WifiDirect: all TCP connect attempts to $goAddress failed")
    }

    private fun onPeerSocketReady(
        endpointId: String,
        remoteName: String,
        socket: Socket,
        isIncoming: Boolean
    ) {
        activeSockets[endpointId] = socket
        activeStreams[endpointId] = DataOutputStream(socket.getOutputStream().buffered())
        connectedPeerMac = endpointId
        Timber.i("WifiDirect: TCP channel ready to $remoteName ($endpointId)")
        // Notify before onConnected so the service can run async blocklist check.
        // For Wi-Fi Direct the OS already accepted; rejectConnection() calls removeGroup().
        onConnectionInitiated?.invoke(endpointId, remoteName)
        onConnected?.invoke(endpointId, remoteName, isIncoming)
        // Start receive loop
        scope.launch { receiveLoop(endpointId, socket) }
    }

    private suspend fun receiveLoop(endpointId: String, socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            while (!socket.isClosed) {
                val length = input.readInt()
                if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
                    Timber.e("WifiDirect: invalid payload length $length from $endpointId — closing")
                    break
                }
                val data = ByteArray(length)
                input.readFully(data)
                Timber.d("WifiDirect: received $length bytes from $endpointId")
                onPayloadReceived?.invoke(endpointId, data)
            }
        } catch (e: IOException) {
            Timber.d("WifiDirect: receive loop ended for $endpointId (${e.message})")
        } finally {
            handlePeerDisconnect(endpointId)
        }
    }

    private fun handleDisconnect() {
        val mac = connectedPeerMac ?: return
        handlePeerDisconnect(mac)
    }

    private fun handlePeerDisconnect(endpointId: String) {
        activeSockets.remove(endpointId)?.runCatching { close() }
        activeStreams.remove(endpointId)
        if (connectedPeerMac == endpointId) connectedPeerMac = null
        Timber.i("WifiDirect: peer disconnected $endpointId")
        onDisconnected?.invoke(endpointId)
    }

    private fun closeAllSockets() {
        activeSockets.forEach { (_, socket) -> socket.runCatching { close() } }
        activeSockets.clear()
        activeStreams.clear()
    }

    // BroadcastReceiver management

    private fun registerBroadcastReceiver() {
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            Timber.d("WifiDirect: BroadcastReceiver registered")
        }
    }

    private fun unregisterBroadcastReceiver() {
        if (receiverRegistered.compareAndSet(true, false)) {
            runCatching { context.unregisterReceiver(receiver) }
            Timber.d("WifiDirect: BroadcastReceiver unregistered")
        }
    }

    // Helpers

    private fun loggingListener(op: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { Timber.d("WifiDirect: $op succeeded") }
        override fun onFailure(reason: Int) { Timber.w("WifiDirect: $op failed (reason=$reason)") }
    }

    companion object {
        /** DNS-SD service type for AURA Wi-Fi Direct discovery. */
        private const val SERVICE_TYPE = "_aura._tcp"

        /** TCP port for profile payload exchange. */
        const val DATA_PORT = 8988

        /**
         * Maximum payload bytes we will accept over the TCP channel.
         * 1 MB is well above any realistic AURA profile; guards against OOM.
         */
        private const val MAX_PAYLOAD_BYTES = 1_048_576

        /** Group Owner intent used when this peer should prefer to be the GO. */
        private const val GROUP_OWNER_INTENT_HIGH = 15

        /** Group Owner intent used when this peer should prefer to be the client. */
        private const val GROUP_OWNER_INTENT_LOW = 0

        /** Number of TCP client connect retries before giving up. */
        private const val MAX_CONNECT_RETRIES = 5

        /** Milliseconds between TCP connect retry attempts. */
        private const val CONNECT_RETRY_DELAY_MS = 600L

        /**
         * SO_TIMEOUT for [ServerSocket.accept]. If no client connects within this
         * window the server socket is closed and the exchange times out gracefully.
         * 30 s is generous for a local P2P link.
         */
        private const val SERVER_ACCEPT_TIMEOUT_MS = 30_000

        /**
         * SO_TIMEOUT applied to every data [Socket]. Prevents the receive loop from
         * blocking indefinitely if the peer stops sending without closing the connection
         * (e.g. process killed on Samsung/MIUI without a TCP FIN). 60 s covers the
         * typical AURA exchange duration with room to spare.
         */
        private const val READ_TIMEOUT_MS = 60_000

        /**
         * Maximum retries for [WifiP2pManager.discoverServices] when the OS returns
         * [WifiP2pManager.BUSY] (reason=2). Samsung One UI and Xiaomi MIUI routinely
         * return BUSY if discovery is restarted within a few seconds.
         */
        private const val DISCOVERY_BUSY_MAX_RETRIES = 3

        /**
         * Delay between [WifiP2pManager.BUSY] discovery retries. 2 s gives the
         * framework time to fully stop the previous discovery session.
         */
        private const val DISCOVERY_BUSY_RETRY_DELAY_MS = 2_000L

        /**
         * Returns true if Wi-Fi Direct is supported and currently enabled on [context]'s device.
         * Use before instantiating [WifiDirectTransport].
         */
        fun isAvailable(context: Context): Boolean {
            val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            return manager != null && context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_WIFI_DIRECT
            )
        }
    }
}
