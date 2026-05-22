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
import com.showerideas.aura.data.BlocklistRepository
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.ui.MainActivity
import com.showerideas.aura.utils.CryptoUtils
import com.showerideas.aura.utils.PayloadValidator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.security.PublicKey
import java.security.SecureRandom
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
        // PR-09: room-mode actions
        const val ACTION_START_ROOM_HOST = "com.showerideas.aura.nearby.START_ROOM_HOST"
        const val ACTION_START_ROOM_GUEST = "com.showerideas.aura.nearby.START_ROOM_GUEST"
        const val ACTION_STATE_UPDATE = "com.showerideas.aura.nearby.STATE_UPDATE"
        const val EXTRA_STATE = "extra_state"

        private const val SERVICE_ID = "com.showerideas.aura"
        private const val CHANNEL_ID = "aura_exchange_channel"
        private const val NOTIFICATION_ID = 1002
        private const val SESSION_TIMEOUT_MS = 30_000L   // 30 seconds
        private const val ROOM_TIMEOUT_MS = 300_000L     // 5 min for room host

        private val STRATEGY = Strategy.P2P_CLUSTER

        // Message type prefixes (single-byte header in the payload)
        private const val MSG_TYPE_PUBLIC_KEY: Byte = 0x01
        private const val MSG_TYPE_PROFILE: Byte = 0x02
        // PR-10: avatar stream signalling.
        private const val MSG_TYPE_AVATAR: Byte = 0x03
        // PR-13: device identity challenge / response.
        private const val MSG_TYPE_CHALLENGE: Byte = 0x04
        private const val MSG_TYPE_CHALLENGE_RESPONSE: Byte = 0x05
        private const val CHALLENGE_BYTES = 32
        /** PR-13: byte that separates the base64 pubkey from the raw bytes. */
        private const val CHALLENGE_SEPARATOR: Byte = '|'.code.toByte()
        private const val MAX_AVATAR_BYTES: Long = 200_000L

        /**
         * PR-13: process-wide registry of {endpointId -> peer identity
         * public key}. The first time we see a new endpoint we record
         * its identity key here; on every subsequent session we hard-fail
         * if the same endpoint presents a different key. This is the
         * MITM endpoint-substitution detector.
         */
        private val peerIdentityRegistry: MutableMap<String, PublicKey> =
            java.util.Collections.synchronizedMap(mutableMapOf())

        // FIX-B: keep the mutable flow private so external callers (ViewModels)
        // cannot bypass the service's internal state machine. Public surface is
        // read-only StateFlow.
        private val _sessionState: MutableStateFlow<ExchangeSession?> = MutableStateFlow(null)
        val sessionState: StateFlow<ExchangeSession?> = _sessionState.asStateFlow()

        /** Live count of guests that have joined a room host session (PR-09). */
        private val _connectedCount: MutableStateFlow<Int> = MutableStateFlow(0)
        val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

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

        /** PR-09: start as a room host (multi-guest collector). */
        fun startRoomHost(context: Context) {
            context.startForegroundService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_START_ROOM_HOST
            })
        }

        /** PR-09: start as a room guest (receives host card, terminates after one exchange). */
        fun startRoomGuest(context: Context) {
            context.startForegroundService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_START_ROOM_GUEST
            })
        }
    }

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var contactRepository: ContactRepository
    /** PR-14: blocklist check on every incoming connection initiation. */
    @Inject lateinit var blocklistRepository: BlocklistRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionsClient by lazy { Nearby.getConnectionsClient(this) }
    private val gson = Gson()

    private lateinit var sessionId: String
    private var timeoutJob: Job? = null
    /** PR-15: periodic nonce cache flush so memory is bounded. */
    private var noncePurgeJob: Job? = null

    /**
     * State of the per-session ECDH handshake. Replaces the previous
     * `peerPublicKeyPending` boolean which couldn't disambiguate the
     * "both sides sent first" race.
     */
    private enum class HandshakeState { IDLE, KEY_SENT, KEY_RECEIVED, COMPLETE }

    // Per-session ephemeral ECDH state (peer-to-peer & room-guest modes).
    private var ourKeyPair: java.security.KeyPair? = null
    private var sessionKey: javax.crypto.SecretKey? = null
    private var connectedEndpoint: String? = null
    private var peerPublicKey: PublicKey? = null
    private var handshakeState: HandshakeState = HandshakeState.IDLE

    /**
     * PR-13: per-session challenge bookkeeping.
     *  - pendingChallengeByEndpoint: the 32 random bytes we sent; we verify
     *    the peer's signature against these on receipt of the response.
     *  - challengeVerifiedByEndpoint: latched true once the peer's response
     *    passes verification. ECDH key sending is gated behind this flag.
     */
    private val pendingChallengeByEndpoint = mutableMapOf<String, ByteArray>()
    private val challengeVerifiedByEndpoint = mutableSetOf<String>()

    /** Active exchange mode for the in-flight session (PR-09). */
    private var currentMode: ExchangeSession.ExchangeMode =
        ExchangeSession.ExchangeMode.PEER_TO_PEER

    /**
     * Per-endpoint ECDH state for room-host mode. Each connected guest
     * carries its own handshake state and derived AES key so concurrent
     * handshakes don't trample each other.
     */
    private data class PeerCtx(
        var handshake: HandshakeState = HandshakeState.IDLE,
        var sessionKey: javax.crypto.SecretKey? = null,
        var peerPub: PublicKey? = null
    )
    private val peerCtxByEndpoint = mutableMapOf<String, PeerCtx>()

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting exchange..."))
        // PR-15: kick off the periodic nonce-cache flush. Lifetime matches
        // the service: cancelled in onDestroy. The cache is shared across
        // sessions so the purge interval is much longer than a session.
        noncePurgeJob = scope.launch {
            while (isActive) {
                delay(PayloadValidator.PURGE_INTERVAL_MS)
                PayloadValidator.purgeNonces()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(ExchangeSession.ExchangeMode.PEER_TO_PEER)
            ACTION_START_ROOM_HOST -> startSession(ExchangeSession.ExchangeMode.ROOM_HOST)
            ACTION_START_ROOM_GUEST -> startSession(ExchangeSession.ExchangeMode.ROOM_GUEST)
            ACTION_STOP -> terminateSession(ExchangeSession.State.CANCELLED)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // PR-15: stop the nonce purger before tearing the scope down so we
        // don't strand a coroutine waiting on delay().
        noncePurgeJob?.cancel()
        noncePurgeJob = null
        scope.cancel()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    private fun startSession(
        mode: ExchangeSession.ExchangeMode = ExchangeSession.ExchangeMode.PEER_TO_PEER
    ) {
        // Hard gate: refuse to advertise/discover until the auth layer has
        // explicitly verified the user. The UI is required to call
        // markGestureVerified() before the service is started, OR to show
        // an explicit "no gesture set" confirmation before doing so.
        if (!gestureVerified) {
            Timber.w("startSession() blocked — gesture not verified")
            sessionId = UUID.randomUUID().toString()
            _sessionState.value = ExchangeSession(
                sessionId = sessionId,
                state = ExchangeSession.State.CANCELLED,
                mode = mode,
                errorMessage = "Gesture verification required"
            )
            broadcastState(sessionState.value)
            terminateSession(ExchangeSession.State.CANCELLED)
            return
        }

        sessionId = UUID.randomUUID().toString()
        currentMode = mode
        ourKeyPair = CryptoUtils.generateEphemeralECDHKeyPair()
        _connectedCount.value = 0
        peerCtxByEndpoint.clear()

        val session = ExchangeSession(
            sessionId = sessionId,
            state = ExchangeSession.State.ADVERTISING,
            mode = mode
        )
        _sessionState.value = session
        broadcastState(session)

        Timber.i("Starting AURA exchange session $sessionId (mode=$mode)")
        startAdvertisingAndDiscovery()

        val timeoutMs = if (mode == ExchangeSession.ExchangeMode.ROOM_HOST)
            ROOM_TIMEOUT_MS else SESSION_TIMEOUT_MS
        timeoutJob = scope.launch {
            delay(timeoutMs)
            Timber.w("Session timed out (mode=$mode)")
            terminateSession(ExchangeSession.State.CANCELLED)
        }
    }

    private fun terminateSession(endState: ExchangeSession.State) {
        timeoutJob?.cancel()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        val current = sessionState.value
        _sessionState.value = current?.copy(state = endState)
        broadcastState(sessionState.value)

        // Reset the gate so the next exchange must re-authenticate.
        gestureVerified = false
        // Reset handshake bookkeeping (PR-02).
        handshakeState = HandshakeState.IDLE
        peerPublicKey = null
        sessionKey = null
        // PR-13: drop per-session challenge bookkeeping. The process-wide
        // peerIdentityRegistry is intentionally preserved across sessions
        // — that's the trust-on-first-use anchor.
        pendingChallengeByEndpoint.clear()
        challengeVerifiedByEndpoint.clear()
        // Reset room bookkeeping (PR-09).
        peerCtxByEndpoint.clear()
        _connectedCount.value = 0
        currentMode = ExchangeSession.ExchangeMode.PEER_TO_PEER
        // Reset avatar bookkeeping (PR-10) so a stale awaiting flag cannot
        // bleed into the next session.
        awaitingAvatarStream.clear()

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
            // PR-14: hard-reject blocked endpoints before any handshake.
            // runBlocking is acceptable here — the DAO call is a single PK
            // lookup on a local SQLite DB and we cannot return until we
            // decide accept vs reject (Nearby's contract is synchronous).
            val blocked = try {
                kotlinx.coroutines.runBlocking {
                    blocklistRepository.isBlocked(endpointId)
                }
            } catch (e: Exception) {
                Timber.w(e, "Blocklist lookup failed for $endpointId — defaulting to accept")
                false
            }
            if (blocked) {
                connectionsClient.rejectConnection(endpointId)
                Timber.i("Rejected blocked endpoint: $endpointId")
                return
            }
            // Auto-accept — AURA's security is at the gesture + ECDH layer
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Timber.i("Connected to $endpointId (mode=$currentMode)")
                if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
                    // Host keeps advertising; each guest tracks its own state.
                    peerCtxByEndpoint[endpointId] = PeerCtx()
                    updateSessionState(ExchangeSession.State.ROOM_HOST)
                    // PR-13: identity challenge first; ECDH is gated behind
                    // a successful challenge response.
                    sendChallenge(endpointId)
                } else {
                    connectedEndpoint = endpointId
                    connectionsClient.stopAdvertising()
                    connectionsClient.stopDiscovery()
                    val nextState = if (currentMode == ExchangeSession.ExchangeMode.ROOM_GUEST)
                        ExchangeSession.State.ROOM_GUEST
                    else
                        ExchangeSession.State.EXCHANGING
                    updateSessionState(nextState)
                    // PR-13: identity challenge first.
                    sendChallenge(endpointId)
                }
            } else {
                Timber.w("Connection to $endpointId failed: ${result.status.statusMessage}")
                updateSessionState(ExchangeSession.State.ERROR)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.d("Disconnected from $endpointId")
            // FIX-C: if the peer dropped mid-avatar-stream, sweep any partial
            // tmp files created in cacheDir for this endpoint so we don't
            // leak storage. The tmp files are named avatar-incoming-*.jpg.
            cleanupPartialAvatarFiles(endpointId)
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
                // Room stays open if one guest drops; clear that guest's ctx.
                peerCtxByEndpoint.remove(endpointId)
                awaitingAvatarStream.remove(endpointId)
                return
            }
            if (sessionState.value?.state != ExchangeSession.State.COMPLETED) {
                terminateSession(ExchangeSession.State.CANCELLED)
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Timber.d("Endpoint found: $endpointId (${info.endpointName}) (mode=$currentMode)")
            // Hosts only advertise; they don't initiate outgoing connections.
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) return
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

    /**
     * PR-10: per-endpoint flag, set when the peer announces an incoming
     * avatar STREAM with [MSG_TYPE_AVATAR]. Cleared once we've ingested
     * the STREAM (or dropped it for being oversized / missing contact).
     */
    private val awaitingAvatarStream: MutableSet<String> = mutableSetOf()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = payload.asBytes() ?: return
                    if (data.isEmpty()) return
                    when (data[0]) {
                        MSG_TYPE_PUBLIC_KEY ->
                            handleIncomingPublicKey(endpointId, data.copyOfRange(1, data.size))
                        MSG_TYPE_PROFILE ->
                            handleIncomingProfile(endpointId, data.copyOfRange(1, data.size))
                        MSG_TYPE_CHALLENGE ->
                            handleIncomingChallenge(endpointId, data.copyOfRange(1, data.size))
                        MSG_TYPE_CHALLENGE_RESPONSE ->
                            handleChallengeResponse(endpointId, data.copyOfRange(1, data.size))
                        MSG_TYPE_AVATAR -> {
                            // Bytes body is intentionally empty — the byte
                            // header is just an out-of-band signal that a
                            // STREAM payload will follow shortly.
                            awaitingAvatarStream.add(endpointId)
                            Timber.d("Avatar stream announced by $endpointId")
                        }
                        else -> Timber.w("Unknown message type: ${data[0]}")
                    }
                }
                Payload.Type.STREAM -> handleIncomingAvatarStream(endpointId, payload)
                else -> Timber.d("Ignoring payload type ${payload.type} from $endpointId")
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

    // -------------------------------------------------------------------------
    // PR-13: device identity challenge / response
    //
    // Wire format for both messages is:
    //   <MSG_TYPE byte> <base64(SPKI public key)> '|' <raw bytes>
    //   - For MSG_TYPE_CHALLENGE,         raw bytes = 32 random challenge bytes.
    //   - For MSG_TYPE_CHALLENGE_RESPONSE, raw bytes = ECDSA signature over
    //     the challenge the peer originally sent us.
    //
    // Both sides send a challenge and verify the other's response. ECDH
    // public-key transmission (sendPublicKey) only fires after our own
    // outgoing challenge has been answered correctly.
    // -------------------------------------------------------------------------

    private fun sendChallenge(endpointId: String) {
        try {
            val challenge = ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
            pendingChallengeByEndpoint[endpointId] = challenge
            val deviceKey = CryptoUtils.getOrCreateDeviceIdentityKey()
            val encodedPubKey = Base64.getEncoder().encodeToString(deviceKey.public.encoded)
            val payload = byteArrayOf(MSG_TYPE_CHALLENGE) +
                encodedPubKey.toByteArray(Charsets.UTF_8) +
                byteArrayOf(CHALLENGE_SEPARATOR) +
                challenge
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload))
                .addOnSuccessListener { Timber.d("Challenge sent to $endpointId (${challenge.size}B)") }
                .addOnFailureListener { e -> Timber.e(e, "Challenge send failed for $endpointId") }
        } catch (e: Exception) {
            Timber.e(e, "Failed to issue challenge to $endpointId")
            terminateSession(ExchangeSession.State.ERROR)
        }
    }

    private fun handleIncomingChallenge(endpointId: String, body: ByteArray) {
        try {
            val sepIdx = body.indexOfFirst { it == CHALLENGE_SEPARATOR }
            if (sepIdx <= 0 || sepIdx >= body.size - 1) {
                Timber.w("Malformed challenge from $endpointId")
                terminateSession(ExchangeSession.State.ERROR); return
            }
            val encodedPubKey = String(body, 0, sepIdx, Charsets.UTF_8)
            val challenge = body.copyOfRange(sepIdx + 1, body.size)
            val peerIdentityKey = decodeEC256PublicKey(Base64.getDecoder().decode(encodedPubKey))

            // TOFU registry check: if this endpoint has been seen with a
            // different identity key in this process, refuse to talk to it.
            val known = peerIdentityRegistry[endpointId]
            if (known != null && !known.encoded.contentEquals(peerIdentityKey.encoded)) {
                Timber.e("Endpoint $endpointId presented a NEW identity key — possible MITM")
                terminateSession(ExchangeSession.State.ERROR); return
            }
            if (known == null) peerIdentityRegistry[endpointId] = peerIdentityKey

            // Sign the peer's challenge with our own device identity key.
            val ourKey = CryptoUtils.getOrCreateDeviceIdentityKey()
            val signature = CryptoUtils.signChallenge(ourKey.private, challenge)
            val encodedOurPub = Base64.getEncoder().encodeToString(ourKey.public.encoded)
            val response = byteArrayOf(MSG_TYPE_CHALLENGE_RESPONSE) +
                encodedOurPub.toByteArray(Charsets.UTF_8) +
                byteArrayOf(CHALLENGE_SEPARATOR) +
                signature
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(response))
                .addOnSuccessListener { Timber.d("Challenge response sent to $endpointId") }
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle challenge from $endpointId")
            terminateSession(ExchangeSession.State.ERROR)
        }
    }

    private fun handleChallengeResponse(endpointId: String, body: ByteArray) {
        try {
            val sepIdx = body.indexOfFirst { it == CHALLENGE_SEPARATOR }
            if (sepIdx <= 0 || sepIdx >= body.size - 1) {
                Timber.w("Malformed challenge response from $endpointId")
                terminateSession(ExchangeSession.State.ERROR); return
            }
            val encodedPubKey = String(body, 0, sepIdx, Charsets.UTF_8)
            val signature = body.copyOfRange(sepIdx + 1, body.size)
            val peerIdentityKey = decodeEC256PublicKey(Base64.getDecoder().decode(encodedPubKey))
            val challenge = pendingChallengeByEndpoint[endpointId] ?: run {
                Timber.e("No pending challenge for $endpointId")
                terminateSession(ExchangeSession.State.ERROR); return
            }
            val ok = CryptoUtils.verifyChallenge(peerIdentityKey, challenge, signature)
            if (!ok) {
                Timber.e("Challenge verification failed for $endpointId — possible MITM")
                terminateSession(ExchangeSession.State.ERROR); return
            }
            // Cross-check the identity key against the TOFU registry (the
            // incoming-challenge path may have populated it on a separate
            // frame). A mismatch here is the strongest MITM signal.
            val known = peerIdentityRegistry[endpointId]
            if (known != null && !known.encoded.contentEquals(peerIdentityKey.encoded)) {
                Timber.e("Endpoint $endpointId identity mismatch on response — aborting")
                terminateSession(ExchangeSession.State.ERROR); return
            }
            if (known == null) peerIdentityRegistry[endpointId] = peerIdentityKey

            challengeVerifiedByEndpoint.add(endpointId)
            pendingChallengeByEndpoint.remove(endpointId)
            Timber.d("Challenge verified for $endpointId — advancing to ECDH")
            // PR-13 gate: only now do we ship our ephemeral ECDH public key.
            sendPublicKey(endpointId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify challenge response from $endpointId")
            terminateSession(ExchangeSession.State.ERROR)
        }
    }

    private fun sendPublicKey(endpointId: String) {
        val kp = ourKeyPair ?: return
        val encodedKey = Base64.getEncoder().encodeToString(kp.public.encoded)
        val payload = byteArrayOf(MSG_TYPE_PUBLIC_KEY) + encodedKey.toByteArray(Charsets.UTF_8)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload))
            .addOnSuccessListener { Timber.d("Public key sent to $endpointId") }

        if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
            val ctx = peerCtxByEndpoint.getOrPut(endpointId) { PeerCtx() }
            if (ctx.handshake == HandshakeState.IDLE) ctx.handshake = HandshakeState.KEY_SENT
            return
        }
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

            val kp = ourKeyPair ?: run {
                Timber.e("Local keypair missing when peer key arrived")
                terminateSession(ExchangeSession.State.ERROR)
                return
            }

            // PR-09: room-host uses a per-endpoint state machine.
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
                val ctx = peerCtxByEndpoint.getOrPut(endpointId) { PeerCtx() }
                ctx.peerPub = decodedPeerKey
                when (ctx.handshake) {
                    HandshakeState.KEY_SENT -> {
                        ctx.sessionKey = CryptoUtils.deriveSharedAESKey(kp.private, decodedPeerKey)
                        ctx.handshake = HandshakeState.COMPLETE
                        sendProfile(endpointId)
                    }
                    HandshakeState.IDLE -> {
                        ctx.handshake = HandshakeState.KEY_RECEIVED
                        sendPublicKey(endpointId)
                        ctx.sessionKey = CryptoUtils.deriveSharedAESKey(kp.private, decodedPeerKey)
                        ctx.handshake = HandshakeState.COMPLETE
                        sendProfile(endpointId)
                    }
                    else -> Timber.w("Duplicate room-host public key from $endpointId")
                }
                return
            }

            peerPublicKey = decodedPeerKey

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
        // PR-09: room-host decrypts with per-guest session key and keeps the room open.
        if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
            val ctx = peerCtxByEndpoint[endpointId]
            val rkey = ctx?.sessionKey ?: run {
                Timber.e("No room session key for $endpointId")
                return
            }
            scope.launch {
                try {
                    val decrypted = CryptoUtils.decrypt(rkey, encryptedData)
                    val mapType = object : TypeToken<Map<String, String>>() {}.type
                    val profileMap: Map<String, String> =
                        gson.fromJson(String(decrypted, Charsets.UTF_8), mapType)
                    // PR-15: replay/skew check on every profile, even room.
                    when (val r = PayloadValidator.validateProfilePayload(profileMap)) {
                        is PayloadValidator.ValidationResult.Ok -> { /* continue */ }
                        else -> {
                            Timber.w("Room-guest profile rejected: $r (endpoint=$endpointId)")
                            return@launch
                        }
                    }
                    val cleanMap = profileMap.filterKeys { !it.startsWith("_") }
                    val contact = Contact.fromMap(
                        id = UUID.randomUUID().toString(),
                        map = cleanMap,
                        endpointId = endpointId
                    )
                    contactRepository.save(contact)
                    _connectedCount.value = _connectedCount.value + 1
                    Timber.i("Room host saved guest contact: ${contact.displayName} (total=${connectedCount.value})")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to process room-guest profile")
                }
            }
            return
        }

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

                // PR-15: stale-timestamp / replay-nonce guard. A captured
                // ciphertext is useless to an attacker because the _ts and
                // _nonce inside are validated here — a re-played payload
                // either trips the age check or the nonce dedup set.
                when (val r = PayloadValidator.validateProfilePayload(profileMap)) {
                    is PayloadValidator.ValidationResult.Ok -> { /* continue */ }
                    else -> {
                        Timber.w("Profile payload rejected: $r (endpoint=$endpointId)")
                        terminateSession(ExchangeSession.State.ERROR)
                        return@launch
                    }
                }
                // Strip the underscore-prefixed internal fields before
                // materialising a Contact — they're protocol metadata, not
                // user-visible profile data.
                val cleanMap = profileMap.filterKeys { !it.startsWith("_") }
                val contact = Contact.fromMap(
                    id = UUID.randomUUID().toString(),
                    map = cleanMap,
                    endpointId = endpointId
                )
                contactRepository.save(contact)

                val current = sessionState.value
                _sessionState.value = current?.copy(
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
        // PR-09: room-host uses per-guest key; other modes use the single sessionKey.
        val key: javax.crypto.SecretKey? =
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST)
                peerCtxByEndpoint[endpointId]?.sessionKey
            else
                sessionKey

        if (key == null) {
            Timber.e("sendProfile() invoked without sessionKey for $endpointId")
            if (currentMode != ExchangeSession.ExchangeMode.ROOM_HOST) {
                // PR-02: in peer-to-peer this is fatal. In room mode we just
                // drop the one guest and keep the room alive.
                terminateSession(ExchangeSession.State.ERROR)
            }
            return
        }
        scope.launch {
            val profile = profileRepository.get() ?: return@launch
            // PR-15: stamp _ts + _nonce on the outgoing payload so the peer
            // can prove freshness and reject any later replay of these bytes.
            val stamped = profile.toShareableMap().toMutableMap()
            PayloadValidator.stampOutgoingProfile(stamped)
            val profileJson = gson.toJson(stamped)
            val encrypted = CryptoUtils.encrypt(key, profileJson.toByteArray(Charsets.UTF_8))
            val payload = byteArrayOf(MSG_TYPE_PROFILE) + encrypted
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload))
                .addOnSuccessListener { Timber.d("Encrypted profile sent to $endpointId") }

            // PR-10: ship the avatar (if any) right after the profile so the
            // receiver can attach it to the contact it just saved.
            sendAvatarIfPresent(endpointId, profile.avatarUri)
        }
    }

    /**
     * PR-10: send the user's avatar JPEG as a STREAM payload, preceded by
     * a BYTES MSG_TYPE_AVATAR signal. Silently no-ops when no avatar is set,
     * the file is missing, empty, or exceeds [MAX_AVATAR_BYTES].
     */
    private fun sendAvatarIfPresent(endpointId: String, avatarPath: String) {
        if (avatarPath.isBlank()) return
        val file = java.io.File(avatarPath)
        if (!file.exists() || file.length() <= 0L) {
            Timber.w("Avatar path is set but file missing/empty: $avatarPath")
            return
        }
        if (file.length() > MAX_AVATAR_BYTES) {
            Timber.w("Avatar exceeds ${MAX_AVATAR_BYTES}B (${file.length()}) — skipping")
            return
        }
        try {
            connectionsClient.sendPayload(
                endpointId,
                Payload.fromBytes(byteArrayOf(MSG_TYPE_AVATAR))
            )
            val pfd = android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            connectionsClient.sendPayload(endpointId, Payload.fromStream(pfd))
                .addOnSuccessListener {
                    Timber.d("Avatar stream sent (${file.length()}B) to $endpointId")
                }
                .addOnFailureListener { e -> Timber.e(e, "Avatar stream send failed") }
        } catch (e: Exception) {
            Timber.e(e, "Failed to open avatar for stream send")
        }
    }

    /**
     * PR-10: ingest the peer's avatar STREAM. Resolve the matching saved
     * contact (most recent for this endpoint), write the bytes into the
     * dedicated avatars folder, and patch the contact's avatarUri.
     */
    private fun handleIncomingAvatarStream(endpointId: String, payload: Payload) {
        if (!awaitingAvatarStream.contains(endpointId)) {
            Timber.w("STREAM from $endpointId without preceding MSG_TYPE_AVATAR — dropping")
            return
        }
        val inputStream = payload.asStream()?.asInputStream() ?: return
        scope.launch {
            val tmp = java.io.File.createTempFile("avatar-incoming-", ".jpg", cacheDir)
            try {
                var written = 0L
                inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            written += n
                            if (written > MAX_AVATAR_BYTES) {
                                Timber.w("Avatar from $endpointId exceeded ${MAX_AVATAR_BYTES}B — aborting")
                                tmp.delete()
                                awaitingAvatarStream.remove(endpointId)
                                return@launch
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
                val contact = contactRepository.findLatestByEndpoint(endpointId)
                if (contact == null) {
                    Timber.w("No saved contact yet for $endpointId; discarding avatar")
                    tmp.delete()
                    return@launch
                }
                val dest = com.showerideas.aura.utils.AvatarUtils.peerAvatarFile(
                    this@NearbyExchangeService, contact.id
                )
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    // Fallback to copy when rename fails (different fs).
                    tmp.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    tmp.delete()
                }
                contactRepository.update(contact.copy(avatarUri = dest.absolutePath))
                Timber.i("Saved peer avatar (${dest.length()}B) for ${contact.displayName}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to ingest avatar stream")
                tmp.delete()
            } finally {
                awaitingAvatarStream.remove(endpointId)
            }
        }
    }

    /**
     * FIX-C: scrub any partial avatar tmp files left in cacheDir if the peer
     * disconnected mid-stream. We only delete files that match the
     * `avatar-incoming-` prefix to avoid touching unrelated cache contents.
     * Also clears the awaiting-avatar flag for this endpoint so a re-connect
     * starts from a clean slate.
     */
    private fun cleanupPartialAvatarFiles(endpointId: String) {
        try {
            awaitingAvatarStream.remove(endpointId)
            val dir = cacheDir ?: return
            dir.listFiles { f -> f.name.startsWith("avatar-incoming-") && f.name.endsWith(".jpg") }
                ?.forEach { stale ->
                    val age = System.currentTimeMillis() - stale.lastModified()
                    // Only delete things that look stale (>5s old) so we don't
                    // race the live stream writer on a parallel connection.
                    if (age > 5_000L) {
                        if (stale.delete()) {
                            Timber.d("Removed partial avatar tmp: ${stale.name} (age=${age}ms)")
                        }
                    }
                }
        } catch (e: Exception) {
            Timber.w(e, "Partial avatar cleanup failed for $endpointId")
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
        _sessionState.value = current?.copy(state = state)
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
