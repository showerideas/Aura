package com.showerideas.aura.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.showerideas.aura.R
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.ui.MainActivity
import com.showerideas.aura.utils.CryptoUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.security.PublicKey
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

/**
 * Manages the full lifecycle of a Nearby Connections exchange session.
 *
 * Protocol (STAR topology, P2P_CLUSTER strategy):
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  0. Gesture (or biometric) auth must mark session verified          │
 * │  1. Both peers ADVERTISE + DISCOVER simultaneously                  │
 * │  2. First mutual discovery → requestConnection()                    │
 * │  3. Both accept → CONNECTED                                         │
 * │  4. Initiator sends ephemeral EC public key (base64 JSON)           │
 * │  5. Responder sends its EC public key back                          │
 * │  6. Both derive shared AES-256 session key via ECDH                 │
 * │  7. Each side encrypts their profile map → sends as BYTES payload   │
 * │  8. Each side decrypts → saves contact → SESSION COMPLETE           │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * The service auto-terminates after [SESSION_TIMEOUT_MS] or on success.
 */
@AndroidEntryPoint
class NearbyExchangeService : Service() {

    companion object {
        const val ACTION_START = "com.showerideas.aura.nearby.START"
        const val ACTION_STOP = "com.showerideas.aura.nearby.STOP"
        const val ACTION_STATE_UPDATE = "com.showerideas.aura.nearby.STATE_UPDATE"
        const val EXTRA_STATE = "extra_state"

        private const val SERVICE_ID = "com.showerideas.aura"
        private const val CHANNEL_ID = "aura_exchange_channel"
        private const val NOTIFICATION_ID = 1002
        private const val SESSION_TIMEOUT_MS = 30_000L   // 30 seconds

        private val STRATEGY = Strategy.P2P_CLUSTER

        // Message type prefixes (single-byte header in the payload)
        private const val MSG_TYPE_PUBLIC_KEY: Byte = 0x01
        private const val MSG_TYPE_PROFILE: Byte = 0x02

        val sessionState: MutableStateFlow<ExchangeSession?> = MutableStateFlow(null)

        /**
         * Gate flag — the exchange service refuses to advance past
         * [startSession] until the UI/auth layer flips this to true via
         * [markGestureVerified]. Reset to false at the end of every session.
         *
         * If no gesture pattern is stored, the UI is responsible for
         * confirming the unprotected exchange with the user and calling
         * [markGestureVerified] explicitly.
         */
        @Volatile
        var gestureVerified: Boolean = false
            private set

        /**
         * Called by [com.showerideas.aura.ui.exchange.ExchangeViewModel]
         * after a successful gesture match (or biometric / acknowledged
         * skip when no pattern is set).
         */
        fun markGestureVerified() {
            gestureVerified = true
            Timber.d("Gesture verified — exchange gate opened")
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var contactRepository: ContactRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionsClient by lazy { Nearby.getConnectionsClient(this) }
    private val gson = Gson()

    private lateinit var sessionId: String
    private var timeoutJob: Job? = null

    /**
     * State of the per-session ECDH handshake. Replaces the previous
     * `peerPublicKeyPending` boolean which couldn't disambiguate the
     * "both sides sent first" race.
     */
    private enum class HandshakeState { IDLE, KEY_SENT, KEY_RECEIVED, COMPLETE }

    // Per-session ephemeral ECDH state
    private var ourKeyPair: java.security.KeyPair? = null
    private var sessionKey: javax.crypto.SecretKey? = null
    private var connectedEndpoint: String? = null
    private var peerPublicKey: PublicKey? = null
    private var handshakeState: HandshakeState = HandshakeState.IDLE

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting exchange..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> terminateSession(ExchangeSession.State.CANCELLED)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    private fun startSession() {
        // Hard gate: refuse to advertise/discover until the auth layer has
        // explicitly verified the user. The UI is required to call
        // markGestureVerified() before the service is started, OR to show
        // an explicit "no gesture set" confirmation before doing so.
        if (!gestureVerified) {
            Timber.w("startSession() blocked — gesture not verified")
            sessionId = UUID.randomUUID().toString()
            sessionState.value = ExchangeSession(
                sessionId = sessionId,
                state = ExchangeSession.State.CANCELLED,
                errorMessage = "Gesture verification required"
            )
            broadcastState(sessionState.value)
            terminateSession(ExchangeSession.State.CANCELLED)
            return
        }

        sessionId = UUID.randomUUID().toString()
        ourKeyPair = CryptoUtils.generateEphemeralECDHKeyPair()

        val session = ExchangeSession(sessionId = sessionId, state = ExchangeSession.State.ADVERTISING)
        sessionState.value = session
        broadcastState(session)

        Timber.i("Starting AURA exchange session $sessionId")
        startAdvertisingAndDiscovery()

        // Auto-cancel after timeout
        timeoutJob = scope.launch {
            delay(SESSION_TIMEOUT_MS)
            Timber.w("Session timed out")
            terminateSession(ExchangeSession.State.CANCELLED)
        }
    }

    private fun terminateSession(endState: ExchangeSession.State) {
        timeoutJob?.cancel()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        val current = sessionState.value
        sessionState.value = current?.copy(state = endState)
        broadcastState(sessionState.value)

        // Reset the gate so the next exchange must re-authenticate.
        gestureVerified = false
        // Reset handshake bookkeeping (PR-02).
        handshakeState = HandshakeState.IDLE
        peerPublicKey = null
        sessionKey = null

        Timber.d("Session terminated with state $endState")
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Nearby Connections — advertising + discovery
    // -------------------------------------------------------------------------

    private fun startAdvertisingAndDiscovery() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        val deviceName = android.provider.Settings.Secure.getString(
            contentResolver, "bluetooth_name"
        ) ?: "AuraUser"

        connectionsClient.startAdvertising(deviceName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener { Timber.d("Advertising started") }
            .addOnFailureListener { e -> Timber.e(e, "Advertising failed") }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { Timber.d("Discovery started") }
            .addOnFailureListener { e -> Timber.e(e, "Discovery failed") }
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle callbacks
    // -------------------------------------------------------------------------

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Timber.d("Connection initiated from $endpointId (${info.endpointName})")
            // Auto-accept — AURA's security is at the gesture + ECDH layer
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Timber.i("Connected to $endpointId")
                connectedEndpoint = endpointId
                connectionsClient.stopAdvertising()
                connectionsClient.stopDiscovery()
                updateSessionState(ExchangeSession.State.EXCHANGING)
                sendPublicKey(endpointId)
            } else {
                Timber.w("Connection to $endpointId failed: ${result.status.statusMessage}")
                updateSessionState(ExchangeSession.State.ERROR)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.d("Disconnected from $endpointId")
            if (sessionState.value?.state != ExchangeSession.State.COMPLETED) {
                terminateSession(ExchangeSession.State.CANCELLED)
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Timber.d("Endpoint found: $endpointId (${info.endpointName})")
            // Only request connection if we haven't connected yet
            if (connectedEndpoint == null) {
                connectionsClient.requestConnection(
                    android.provider.Settings.Secure.getString(contentResolver, "bluetooth_name")
                        ?: "AuraUser",
                    endpointId,
                    connectionLifecycleCallback
                )
                    .addOnSuccessListener { Timber.d("Connection request sent to $endpointId") }
                    .addOnFailureListener { e -> Timber.e(e, "Failed to request connection") }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.d("Endpoint lost: $endpointId")
        }
    }

    // -------------------------------------------------------------------------
    // Payload handling
    // -------------------------------------------------------------------------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val data = payload.asBytes() ?: return
            if (data.isEmpty()) return

            when (data[0]) {
                MSG_TYPE_PUBLIC_KEY -> handleIncomingPublicKey(endpointId, data.copyOfRange(1, data.size))
                MSG_TYPE_PROFILE -> handleIncomingProfile(endpointId, data.copyOfRange(1, data.size))
                else -> Timber.w("Unknown message type: ${data[0]}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op for small BYTES payloads
        }
    }

    // -------------------------------------------------------------------------
    // ECDH key exchange
    //
    // PR-02: the previous boolean-based race handling allowed a symmetric
    // "both sides sent first" scenario to drop one peer's response. The new
    // [HandshakeState] machine captures the four possible local states:
    //
    //   IDLE          — nothing sent or received yet
    //   KEY_SENT      — we sent ours, awaiting peer's key
    //   KEY_RECEIVED  — peer's key arrived first, we still need to send ours
    //   COMPLETE      — both keys exchanged, sessionKey derived
    //
    // Both peers ultimately converge on COMPLETE regardless of order.
    // -------------------------------------------------------------------------

    private fun sendPublicKey(endpointId: String) {
        val kp = ourKeyPair ?: return
        val encodedKey = Base64.getEncoder().encodeToString(kp.public.encoded)
        val payload = byteArrayOf(MSG_TYPE_PUBLIC_KEY) + encodedKey.toByteArray(Charsets.UTF_8)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload))
            .addOnSuccessListener { Timber.d("Public key sent to $endpointId") }
        // Only flip to KEY_SENT if we haven't already received the peer's key.
        if (handshakeState == HandshakeState.IDLE) {
            handshakeState = HandshakeState.KEY_SENT
        }
    }

    private fun handleIncomingPublicKey(endpointId: String, data: ByteArray) {
        try {
            val encodedKey = String(data, Charsets.UTF_8)
            val keyBytes = Base64.getDecoder().decode(encodedKey)
            val decodedPeerKey = decodeEC256PublicKey(keyBytes)
            peerPublicKey = decodedPeerKey

            val kp = ourKeyPair ?: run {
                Timber.e("Local keypair missing when peer key arrived")
                terminateSession(ExchangeSession.State.ERROR)
                return
            }

            when (handshakeState) {
                HandshakeState.KEY_SENT -> {
                    // We sent first — peer's reply has now arrived. Derive the
                    // session key and ship the profile.
                    sessionKey = CryptoUtils.deriveSharedAESKey(kp.private, decodedPeerKey)
                    handshakeState = HandshakeState.COMPLETE
                    Timber.d("Handshake COMPLETE (we sent first)")
                    sendProfile(endpointId)
                }
                HandshakeState.IDLE -> {
                    // Peer's key beat ours to the wire. Store it, send ours,
                    // then derive the session key. We deliberately set state
                    // to KEY_RECEIVED first so sendPublicKey() will not
                    // overwrite the order tracking.
                    handshakeState = HandshakeState.KEY_RECEIVED
                    sendPublicKey(endpointId)
                    sessionKey = CryptoUtils.deriveSharedAESKey(kp.private, decodedPeerKey)
                    handshakeState = HandshakeState.COMPLETE
                    Timber.d("Handshake COMPLETE (peer sent first)")
                    sendProfile(endpointId)
                }
                HandshakeState.KEY_RECEIVED, HandshakeState.COMPLETE -> {
                    Timber.w("Duplicate or out-of-order public key (state=$handshakeState) — ignoring")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process peer public key")
            terminateSession(ExchangeSession.State.ERROR)
        }
    }

    private fun handleIncomingProfile(endpointId: String, encryptedData: ByteArray) {
        val key = sessionKey ?: run {
            Timber.e("No session key available — cannot decrypt profile")
            return
        }
        scope.launch {
            try {
                val decrypted = CryptoUtils.decrypt(key, encryptedData)
                val mapType = object : TypeToken<Map<String, String>>() {}.type
                val profileMap: Map<String, String> =
                    gson.fromJson(String(decrypted, Charsets.UTF_8), mapType)

                val contact = Contact.fromMap(
                    id = UUID.randomUUID().toString(),
                    map = profileMap,
                    endpointId = endpointId
                )
                contactRepository.save(contact)

                val current = sessionState.value
                sessionState.value = current?.copy(
                    state = ExchangeSession.State.COMPLETED,
                    receivedContact = contact
                )
                broadcastState(sessionState.value)
                Timber.i("Exchange complete — saved contact: ${contact.displayName}")

                // Reset gate on success too.
                gestureVerified = false

                delay(500)
                stopSelf()
            } catch (e: Exception) {
                Timber.e(e, "Failed to process incoming profile")
                terminateSession(ExchangeSession.State.ERROR)
            }
        }
    }

    private fun sendProfile(endpointId: String) {
        // PR-02: never attempt to send a profile if the ECDH handshake
        // didn't actually finish. Without a session key the recipient
        // could not decrypt, which used to fail silently.
        val key = sessionKey ?: run {
            Timber.e("sendProfile() invoked without sessionKey — aborting session")
            terminateSession(ExchangeSession.State.ERROR)
            return
        }
        scope.launch {
            val profile = profileRepository.get() ?: return@launch
            val profileJson = gson.toJson(profile.toShareableMap())
            val encrypted = CryptoUtils.encrypt(key, profileJson.toByteArray(Charsets.UTF_8))
            val payload = byteArrayOf(MSG_TYPE_PROFILE) + encrypted
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload))
                .addOnSuccessListener { Timber.d("Encrypted profile sent") }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun decodeEC256PublicKey(encoded: ByteArray): PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        return java.security.KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun updateSessionState(state: ExchangeSession.State) {
        val current = sessionState.value
        sessionState.value = current?.copy(state = state)
        broadcastState(sessionState.value)
        updateNotification(state.name)
    }

    private fun broadcastState(session: ExchangeSession?) {
        sendBroadcast(Intent(ACTION_STATE_UPDATE).apply {
            `package` = packageName
            putExtra(EXTRA_STATE, session?.state?.name)
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AURA Exchange",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active contact exchange in progress" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AURA Exchange")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_aura_small)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
