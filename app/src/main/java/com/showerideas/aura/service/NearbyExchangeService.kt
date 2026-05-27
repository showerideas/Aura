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
import com.showerideas.aura.service.NotificationChannels
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.showerideas.aura.R
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.data.BlocklistRepository
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ExchangeAuditRepository
import com.showerideas.aura.data.KnownPeerRepository
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.ExchangeAuditEntry
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.model.MergeEvent
import com.showerideas.aura.ui.MainActivity
import com.showerideas.aura.crypto.HybridKemEngine
import com.showerideas.aura.utils.CryptoUtils
import com.showerideas.aura.utils.DoubleRatchetState
import com.showerideas.aura.utils.IdentityRotationDetector
import com.showerideas.aura.utils.PayloadValidator
import com.showerideas.aura.utils.SasVerifier
import com.showerideas.aura.utils.vibrateDouble
import com.showerideas.aura.utils.vibrateShort
import javax.crypto.spec.SecretKeySpec
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Manages the full lifecycle of an exchange session.
 *
 * Protocol (STAR topology, transport-agnostic):
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  0. Gesture (or biometric) auth must mark session verified          │
 * │  1. Both peers ADVERTISE + DISCOVER simultaneously                  │
 * │  2. First mutual discovery → requestConnection()                    │
 * │  3. Both accept → CONNECTED                                         │
 * │  4. ECDSA challenge/response — device identity verification         │
 * │  5. Initiator sends KEM HELLO (ML-KEM-768+X25519 hybrid public key) │
 * │  6. Responder sends KEM HELLO_ACK (ciphertext + negotiated version) │
 * │  7. Both derive 32-byte shared secret via post-quantum hybrid KEM   │
 * │  8. SAS PIN verification (SHA-256 of shared secret, verbal match)   │
 * │  9. Each side encrypts their profile map → sends as BYTES payload   │
 * │ 10. Each side decrypts → saves contact → SESSION COMPLETE           │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Transport is injected via Hilt:
 *  - `gms` flavor → [NearbyConnectionsTransport] (Google Nearby Connections)
 *  - `foss` flavor → [WifiDirectTransport] (Wi-Fi Direct, no GMS dependency)
 *
 * The service auto-terminates after [SESSION_TIMEOUT_MS] or on success.
 */
@AndroidEntryPoint
class NearbyExchangeService : Service() {

    companion object {
        const val ACTION_START            = "com.showerideas.aura.nearby.START"
        const val ACTION_STOP             = "com.showerideas.aura.nearby.STOP"
        const val ACTION_START_ROOM_HOST  = "com.showerideas.aura.nearby.START_ROOM_HOST"
        const val ACTION_START_ROOM_GUEST = "com.showerideas.aura.nearby.START_ROOM_GUEST"
        const val ACTION_STATE_UPDATE     = "com.showerideas.aura.nearby.STATE_UPDATE"
        const val EXTRA_STATE             = "extra_state"
        const val ACTION_CONFIRM_SAS      = "com.showerideas.aura.nearby.CONFIRM_SAS"
        const val ACTION_ABORT_SAS        = "com.showerideas.aura.nearby.ABORT_SAS"
        const val ACTION_GESTURE_VERIFIED = "com.showerideas.aura.nearby.GESTURE_VERIFIED"

        private const val SERVICE_ID         = "com.showerideas.aura"
        private const val CHANNEL_ID = NotificationChannels.CHANNEL_EXCHANGE
        private const val NOTIFICATION_ID    = 1002
        private const val SESSION_TIMEOUT_MS = 30_000L
        private const val ROOM_TIMEOUT_MS    = 300_000L

        // Message type prefixes (single-byte header in the payload)
        private const val MSG_TYPE_PUBLIC_KEY:         Byte = 0x01
        private const val MSG_TYPE_PROFILE:            Byte = 0x02
        private const val MSG_TYPE_AVATAR:             Byte = 0x03
        private const val MSG_TYPE_CHALLENGE:          Byte = 0x04
        private const val MSG_TYPE_CHALLENGE_RESPONSE: Byte = 0x05
        private const val CHALLENGE_BYTES = 32
        private const val CHALLENGE_SEPARATOR: Byte = '|'.code.toByte()
        private const val MAX_AVATAR_BYTES: Long = 200_000L

        private val _sessionState: MutableStateFlow<ExchangeSession?> = MutableStateFlow(null)
        val sessionState: StateFlow<ExchangeSession?> = _sessionState.asStateFlow()

        private val _connectedCount: MutableStateFlow<Int> = MutableStateFlow(0)
        val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

        // NFC bootstrap — set by MainActivity.onResume / cleared on session start

        @Volatile var nfcLocalKeyPair: java.security.KeyPair? = null
        @Volatile var pendingNfcBootstrap: NfcExchangeHelper.NfcBootstrap? = null

        fun markGestureVerified(context: Context) {
            context.startService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_GESTURE_VERIFIED
            })
        }

        fun confirmSas(context: Context) {
            context.startService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_CONFIRM_SAS
            })
        }

        fun abortSas(context: Context) {
            context.startService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_ABORT_SAS
            })
        }


        /**
         * Testing only: directly set [sessionState] to drive UI without a real
         * Nearby Connections session. Used by Espresso tests to trigger the SAS
         * dialog without a live peer. Never call this in production code.
         */
        @VisibleForTesting
        internal fun injectTestSessionState(session: ExchangeSession?) {
            _sessionState.value = session
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

        fun startRoomHost(context: Context) {
            context.startForegroundService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_START_ROOM_HOST
            })
        }

        fun startRoomGuest(context: Context) {
            context.startForegroundService(Intent(context, NearbyExchangeService::class.java).apply {
                action = ACTION_START_ROOM_GUEST
            })
        }
    }

    // Injected dependencies

    @Inject lateinit var transport: NearbyTransport
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var blocklistRepository: BlocklistRepository
    @Inject lateinit var knownPeerRepository: KnownPeerRepository
    @Inject lateinit var identityRotationDetector: IdentityRotationDetector
    @Inject lateinit var auditRepository: ExchangeAuditRepository
    @Inject lateinit var authPreferences: AuthPreferences
    @Inject lateinit var hybridKemEngine: HybridKemEngine

    @Volatile private var gestureVerified: Boolean = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private lateinit var sessionId: String
    private var timeoutJob: Job? = null
    private var noncePurgeJob: Job? = null

    private enum class HandshakeState { IDLE, KEY_SENT, KEY_RECEIVED, COMPLETE }

    /** Ephemeral EC keypair — used ONLY for NFC-bootstrapped sessions (classical ECDH). */
    private var ourKeyPair: java.security.KeyPair? = null
    /** Active hybrid KEM session for Nearby/Wi-Fi Direct/BLE exchanges. */
    @Volatile private var kemSession: HybridKemEngine.KemSession? = null
    @Volatile private var sessionKey: javax.crypto.SecretKey? = null
    @Volatile private var connectedEndpoint: String? = null
    @Volatile private var peerPublicKey: PublicKey? = null
    @Volatile private var handshakeState: HandshakeState = HandshakeState.IDLE

    @Volatile private var sendRatchet: DoubleRatchetState? = null
    @Volatile private var recvRatchet: DoubleRatchetState? = null

    @Volatile private var pendingSasEndpointId: String? = null

    @Volatile private var sessionPeerKeyHash: String? = null
    @Volatile private var sessionChannel: String = ExchangeAuditEntry.CHANNEL_NEARBY

    private val connectionRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private val MAX_PROFILE_PAYLOAD_BYTES = 65_536

    private val pendingChallengeByEndpoint  = ConcurrentHashMap<String, ByteArray>()
    private val challengeVerifiedByEndpoint: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingConnections: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var currentMode: ExchangeSession.ExchangeMode =
        ExchangeSession.ExchangeMode.PEER_TO_PEER

    private data class PeerCtx(
        var handshake: HandshakeState = HandshakeState.IDLE,
        var sessionKey: javax.crypto.SecretKey? = null,
        var kemSession: HybridKemEngine.KemSession? = null,
        var peerPub: PublicKey? = null,
        var sendRatchet: DoubleRatchetState? = null,
        var recvRatchet: DoubleRatchetState? = null
    )
    private val peerCtxByEndpoint = ConcurrentHashMap<String, PeerCtx>()
    private val awaitingAvatarStream: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Service lifecycle

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))
        noncePurgeJob = scope.launch {
            while (isActive) {
                delay(PayloadValidator.PURGE_INTERVAL_MS)
                PayloadValidator.purgeNonces()
            }
        }
        wireTransportCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START            -> startSession(ExchangeSession.ExchangeMode.PEER_TO_PEER)
            ACTION_START_ROOM_HOST  -> startSession(ExchangeSession.ExchangeMode.ROOM_HOST)
            ACTION_START_ROOM_GUEST -> startSession(ExchangeSession.ExchangeMode.ROOM_GUEST)
            ACTION_STOP             -> terminateSession(ExchangeSession.State.CANCELLED)
            ACTION_CONFIRM_SAS -> {
                val ep = pendingSasEndpointId
                if (ep != null) {
                    scope.launch { auditRepository.logSasEvent(confirmed = true, channel = ExchangeAuditEntry.CHANNEL_NEARBY) }
                    Timber.i("SAS confirmed — sending profile to $ep")
                    pendingSasEndpointId = null
                    sendProfile(ep)
                } else {
                    Timber.w("ACTION_CONFIRM_SAS: no pending SAS endpoint")
                }
            }
            ACTION_ABORT_SAS -> {
                Timber.w("SAS mismatch — aborting (possible MITM)")
                pendingSasEndpointId = null
                terminateSession(
                    ExchangeSession.State.ERROR,
                    "Security check failed. The codes didn't match — possible MITM attack.",
                    auditErrorCode = ExchangeAuditEntry.ERR_SAS_MISMATCH
                )
            }
            ACTION_GESTURE_VERIFIED -> {
                gestureVerified = true
                scope.launch { authPreferences.setGestureGateOpen(true) }
                Timber.d("Gesture gate opened")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        noncePurgeJob?.cancel()
        noncePurgeJob = null
        scope.cancel()
        transport.stopAllEndpoints()
        transport.stopAdvertising()
        transport.stopDiscovery()
        super.onDestroy()
    }

    // Transport callback wiring — called once in onCreate

    /**
     * Wire all [NearbyTransport] callbacks to the service's session logic.
     *
     * Called once in [onCreate]. The lambdas capture `this` so they see the
     * latest per-session state fields on every invocation.
     */
    private fun wireTransportCallbacks() {

        // onConnectionInitiated — async blocklist check before accept/reject
        transport.onConnectionInitiated = onConnectionInitiated@{ endpointId, _ ->
            Timber.d("Connection initiated from $endpointId")
            pendingConnections.add(endpointId)
            scope.launch {
                val blocked = try {
                    blocklistRepository.isBlocked(endpointId)
                } catch (e: Exception) {
                    Timber.w(e, "Blocklist lookup failed for $endpointId — defaulting to accept")
                    false
                }
                // Peer may have disconnected while we were checking
                if (!pendingConnections.remove(endpointId)) return@launch
                if (blocked) {
                    transport.rejectConnection(endpointId)
                    Timber.i("Rejected blocked endpoint: $endpointId")
                    terminateSession(
                        ExchangeSession.State.ERROR,
                        "Connection rejected: Device is blocked",
                        auditOutcome = ExchangeAuditEntry.OUTCOME_BLOCKED
                    )
                } else {
                    transport.acceptConnection(endpointId)
                }
            }
        }

        // onConnected — connection established, start challenge/response
        transport.onConnected = { endpointId, _, _ ->
            Timber.i("Connected to $endpointId (mode=$currentMode)")
            vibrateDouble()
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
                peerCtxByEndpoint[endpointId] = PeerCtx()
                updateSessionState(ExchangeSession.State.CONNECTING)
                sendChallenge(endpointId)
            } else {
                connectedEndpoint = endpointId
                transport.stopAdvertising()
                transport.stopDiscovery()
                updateSessionState(ExchangeSession.State.CONNECTING)
                sendChallenge(endpointId)
            }
        }

        // onDisconnected
        transport.onDisconnected = disconnectHandler@{ endpointId ->
            Timber.d("Disconnected from $endpointId")
            pendingConnections.remove(endpointId)
            cleanupPartialAvatarFiles(endpointId)
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
                peerCtxByEndpoint.remove(endpointId)
                awaitingAvatarStream.remove(endpointId)
                pendingChallengeByEndpoint.remove(endpointId)
                return@disconnectHandler
            }
            if (sessionState.value?.state != ExchangeSession.State.COMPLETED) {
                terminateSession(ExchangeSession.State.CANCELLED)
            }
        }

        // onEndpointFound — initiate outgoing connection (CAS guard)
        transport.onEndpointFound = endpointFoundHandler@{ endpointId, _ ->
            Timber.d("Endpoint found: $endpointId (mode=$currentMode)")
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) return@endpointFoundHandler
            if (!connectionRequested.compareAndSet(false, true)) {
                Timber.d("Connection already requested — ignoring $endpointId")
                return@endpointFoundHandler
            }
            val localName = android.provider.Settings.Secure.getString(
                contentResolver, "bluetooth_name"
            ) ?: "AuraUser"
            transport.requestConnection(localName, endpointId)
        }

        // onPayloadReceived — dispatch on first-byte message type
        transport.onPayloadReceived = payloadHandler@{ endpointId, data ->
            if (data.isEmpty()) return@payloadHandler
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
                    awaitingAvatarStream.add(endpointId)
                    Timber.d("Avatar stream announced by $endpointId")
                }
                else -> Timber.w("Unknown message type: ${data[0]}")
            }
        }

        // Avatar STREAM — gms flavor only; no-op on foss / test doubles
        transport.onAvatarStreamReceived = { endpointId, stream ->
            handleIncomingAvatarStream(endpointId, stream)
        }
    }

    // Session management

    private fun startSession(
        mode: ExchangeSession.ExchangeMode = ExchangeSession.ExchangeMode.PEER_TO_PEER
    ) {
        if (!gestureVerified) {
            Timber.w("startSession() blocked — gesture not verified")
            sessionId = UUID.randomUUID().toString()
            _sessionState.value = ExchangeSession(
                sessionId    = sessionId,
                state        = ExchangeSession.State.CANCELLED,
                mode         = mode,
                errorMessage = "Gesture verification required"
            )
            broadcastState(sessionState.value)
            terminateSession(ExchangeSession.State.CANCELLED)
            return
        }

        sessionId     = UUID.randomUUID().toString()
        currentMode   = mode
        sessionPeerKeyHash = null
        sessionChannel = when (mode) {
            ExchangeSession.ExchangeMode.ROOM_HOST    -> ExchangeAuditEntry.CHANNEL_ROOM_HOST
            ExchangeSession.ExchangeMode.ROOM_GUEST   -> ExchangeAuditEntry.CHANNEL_ROOM_GUEST
            ExchangeSession.ExchangeMode.PEER_TO_PEER -> ExchangeAuditEntry.CHANNEL_NEARBY
        }
        scope.launch { auditRepository.pruneOldEntries() }

        // NFC bootstrap — consume the keypair + bootstrap once per session.
        // ourKeyPair is only needed when an NFC-bootstrapped session supplies a
        // pre-generated EC keypair. All other exchanges use HybridKemEngine (PQ hybrid KEM).
        val nfcBootstrap = pendingNfcBootstrap.also { pendingNfcBootstrap = null }
        ourKeyPair = nfcLocalKeyPair?.also { nfcLocalKeyPair = null }
            ?: if (nfcBootstrap != null) CryptoUtils.generateEphemeralECDHKeyPair() else null
        kemSession = null

        if (nfcBootstrap != null) {
            sessionChannel = ExchangeAuditEntry.CHANNEL_NFC
            Timber.i("NFC bootstrap present for session $sessionId — skipping Nearby key exchange")
            runCatching {
                peerPublicKey = decodeEC256PublicKey(nfcBootstrap.peerPublicKeyBytes)
                sessionKey    = CryptoUtils.deriveSharedAESKey(ourKeyPair!!.private, peerPublicKey!!)
                val (sr, rr) = initDirectionalRatchets(sessionKey!!, isInitiator = false)
                sendRatchet = sr; recvRatchet = rr
                handshakeState = HandshakeState.COMPLETE
                Timber.d("NFC-derived session key ready (peerSession=${nfcBootstrap.peerSessionUuid})")
            }.onFailure {
                Timber.e(it, "Failed to decode NFC peer key — falling back to key exchange")
                peerPublicKey  = null
                sessionKey     = null
                handshakeState = HandshakeState.IDLE
            }
        }

        _connectedCount.value = 0
        peerCtxByEndpoint.clear()

        val session = ExchangeSession(sessionId = sessionId, state = ExchangeSession.State.ADVERTISING, mode = mode)
        _sessionState.value = session
        broadcastState(session)

        Timber.i("Starting AURA exchange session $sessionId (mode=$mode)")
        startAdvertisingAndDiscovery()

        val timeoutMs = if (mode == ExchangeSession.ExchangeMode.ROOM_HOST) ROOM_TIMEOUT_MS else SESSION_TIMEOUT_MS
        timeoutJob = scope.launch {
            delay(timeoutMs)
            Timber.w("Session timed out (mode=$mode)")
            terminateSession(ExchangeSession.State.CANCELLED, auditOutcome = ExchangeAuditEntry.OUTCOME_TIMEOUT)
        }
    }

    private fun terminateSession(
        endState: ExchangeSession.State,
        errorMessage: String? = null,
        auditOutcome: String? = null,
        auditErrorCode: String? = null
    ) {
        timeoutJob?.cancel()
        transport.stopAllEndpoints()
        transport.stopAdvertising()
        transport.stopDiscovery()

        val current      = sessionState.value
        val finalMessage = errorMessage
            ?: if (endState == ExchangeSession.State.ERROR) current?.errorMessage else null
        _sessionState.value = current?.copy(state = endState, errorMessage = finalMessage)
        broadcastState(sessionState.value)

        val capturedHash    = sessionPeerKeyHash
        val capturedChannel = sessionChannel
        val outcomeToLog    = auditOutcome ?: when (endState) {
            ExchangeSession.State.COMPLETED -> ExchangeAuditEntry.OUTCOME_SUCCESS
            ExchangeSession.State.ERROR     -> ExchangeAuditEntry.OUTCOME_FAILED
            ExchangeSession.State.CANCELLED -> null
            else -> null
        }
        if (outcomeToLog != null) {
            scope.launch {
                when (outcomeToLog) {
                    ExchangeAuditEntry.OUTCOME_SUCCESS  -> auditRepository.logSuccess(capturedHash, capturedChannel)
                    ExchangeAuditEntry.OUTCOME_TIMEOUT  -> auditRepository.logTimeout(capturedHash, capturedChannel)
                    ExchangeAuditEntry.OUTCOME_BLOCKED  -> auditRepository.logBlocked(capturedHash, capturedChannel)
                    else -> auditRepository.logFailure(capturedHash, auditErrorCode, capturedChannel)
                }
            }
        }

        gestureVerified = false
        scope.launch { authPreferences.setGestureGateOpen(false) }
        connectionRequested.set(false)
        handshakeState = HandshakeState.IDLE
        kemSession     = null
        ourKeyPair     = null
        peerPublicKey  = null
        sessionKey     = null
        sendRatchet    = null
        recvRatchet    = null
        pendingSasEndpointId = null
        sessionPeerKeyHash   = null
        sessionChannel = ExchangeAuditEntry.CHANNEL_NEARBY
        pendingChallengeByEndpoint.clear()
        challengeVerifiedByEndpoint.clear()
        peerCtxByEndpoint.clear()
        _connectedCount.value = 0
        currentMode    = ExchangeSession.ExchangeMode.PEER_TO_PEER
        awaitingAvatarStream.clear()

        Timber.d("Session terminated: $endState")
        stopSelf()
    }

    // Advertising + discovery

    private fun startAdvertisingAndDiscovery() {
        val deviceName = android.provider.Settings.Secure.getString(
            contentResolver, "bluetooth_name"
        ) ?: "AuraUser"
        transport.startAdvertising(deviceName, SERVICE_ID)
        transport.startDiscovery(SERVICE_ID)
        // Transition to DISCOVERING immediately — the transport fires internally on success.
        // For WifiDirect this mirrors the real discovery start; for Nearby Connections
        // the Nearby SDK fires the success listener shortly after which is fine.
        updateSessionState(ExchangeSession.State.DISCOVERING)
    }

    // Device identity challenge / response

    private fun sendChallenge(endpointId: String) {
        try {
            val challenge    = ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
            pendingChallengeByEndpoint[endpointId] = challenge
            val deviceKey    = CryptoUtils.getOrCreateDeviceIdentityKey()
            val encodedPubKey = Base64.getEncoder().encodeToString(deviceKey.public.encoded)
            val payload = byteArrayOf(MSG_TYPE_CHALLENGE) +
                encodedPubKey.toByteArray(Charsets.UTF_8) +
                byteArrayOf(CHALLENGE_SEPARATOR) +
                challenge
            transport.sendBytes(endpointId, payload)
            Timber.d("Challenge sent to $endpointId (${challenge.size}B)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to issue challenge to $endpointId")
            terminateSession(ExchangeSession.State.ERROR)
        }
    }

    private fun handleIncomingChallenge(endpointId: String, body: ByteArray) {
        scope.launch {
            if (challengeVerifiedByEndpoint.contains(endpointId)) {
                Timber.w("Re-challenge from already-verified endpoint $endpointId — ignored")
                return@launch
            }
            try {
                val sepIdx = body.indexOfFirst { it == CHALLENGE_SEPARATOR }
                if (sepIdx <= 0 || sepIdx >= body.size - 1) {
                    Timber.w("Malformed challenge from $endpointId")
                    terminateSession(ExchangeSession.State.ERROR); return@launch
                }
                val encodedPubKey = String(body, 0, sepIdx, Charsets.UTF_8)
                val challenge     = body.copyOfRange(sepIdx + 1, body.size)
                if (challenge.size > CHALLENGE_BYTES * 4) {
                    Timber.w("Oversized challenge from $endpointId (${challenge.size}B) — rejecting")
                    terminateSession(ExchangeSession.State.ERROR); return@launch
                }
                val peerIdentityKey = decodeEC256PublicKey(Base64.getDecoder().decode(encodedPubKey))

                if (blocklistRepository.isBlockedByKeyHash(peerIdentityKey)) {
                    sessionPeerKeyHash = CryptoUtils.identityKeyHash(peerIdentityKey)
                    Timber.i("Rejected blocked identity key for endpoint $endpointId")
                    terminateSession(
                        ExchangeSession.State.ERROR,
                        "This device is blocked. Exchange rejected.",
                        auditOutcome = ExchangeAuditEntry.OUTCOME_BLOCKED
                    )
                    return@launch
                }

                when (val result = knownPeerRepository.getIdentityKey(endpointId)) {
                    is KnownPeerRepository.IdentityKeyResult.Found -> {
                        if (!result.key.encoded.contentEquals(peerIdentityKey.encoded)) {
                            sessionPeerKeyHash = CryptoUtils.identityKeyHash(peerIdentityKey)
                            Timber.e("MITM: $endpointId presented different identity key")
                            terminateSession(
                                ExchangeSession.State.ERROR,
                                "Security alert: This device's identity has changed. Possible man-in-the-middle attack — exchange rejected.",
                                auditErrorCode = ExchangeAuditEntry.ERR_MITM_DETECTED
                            )
                            return@launch
                        }
                    }
                    is KnownPeerRepository.IdentityKeyResult.NotFound ->
                        knownPeerRepository.upsertIdentityKey(endpointId, peerIdentityKey)
                    is KnownPeerRepository.IdentityKeyResult.Corrupt -> {
                        Timber.e(result.cause, "Corrupt TOFU record for ${result.endpointId} — aborting")
                        terminateSession(
                            ExchangeSession.State.ERROR,
                            "Peer identity record is corrupt. Exchange aborted for your safety.",
                            auditErrorCode = ExchangeAuditEntry.ERR_CRYPTO_ERROR
                        )
                        return@launch
                    }
                }

                val ourKey       = CryptoUtils.getOrCreateDeviceIdentityKey()
                val signature    = CryptoUtils.signChallenge(ourKey.private, challenge)
                val encodedOurPub = Base64.getEncoder().encodeToString(ourKey.public.encoded)
                val response = byteArrayOf(MSG_TYPE_CHALLENGE_RESPONSE) +
                    encodedOurPub.toByteArray(Charsets.UTF_8) +
                    byteArrayOf(CHALLENGE_SEPARATOR) +
                    signature
                transport.sendBytes(endpointId, response)
                Timber.d("Challenge response sent to $endpointId")
            } catch (e: Exception) {
                Timber.e(e, "Failed to handle challenge from $endpointId")
                terminateSession(ExchangeSession.State.ERROR)
            }
        }
    }

    private fun handleChallengeResponse(endpointId: String, body: ByteArray) {
        scope.launch {
            try {
                val sepIdx = body.indexOfFirst { it == CHALLENGE_SEPARATOR }
                if (sepIdx <= 0 || sepIdx >= body.size - 1) {
                    Timber.w("Malformed challenge response from $endpointId")
                    terminateSession(ExchangeSession.State.ERROR); return@launch
                }
                val encodedPubKey   = String(body, 0, sepIdx, Charsets.UTF_8)
                val signature       = body.copyOfRange(sepIdx + 1, body.size)
                val peerIdentityKey = decodeEC256PublicKey(Base64.getDecoder().decode(encodedPubKey))
                val challenge       = pendingChallengeByEndpoint.remove(endpointId) ?: run {
                    Timber.e("No pending challenge for $endpointId")
                    terminateSession(ExchangeSession.State.ERROR); return@launch
                }
                val ok = CryptoUtils.verifyChallenge(peerIdentityKey, challenge, signature)
                if (!ok) {
                    sessionPeerKeyHash = CryptoUtils.identityKeyHash(peerIdentityKey)
                    Timber.e("Challenge verification failed for $endpointId — possible MITM")
                    terminateSession(
                        ExchangeSession.State.ERROR,
                        "Security alert: Authentication failed — possible man-in-the-middle attack.",
                        auditErrorCode = ExchangeAuditEntry.ERR_MITM_DETECTED
                    )
                    return@launch
                }

                when (val result = knownPeerRepository.getIdentityKey(endpointId)) {
                    is KnownPeerRepository.IdentityKeyResult.Found -> {
                        if (!result.key.encoded.contentEquals(peerIdentityKey.encoded)) {
                            sessionPeerKeyHash = CryptoUtils.identityKeyHash(peerIdentityKey)
                            Timber.e("Endpoint $endpointId identity mismatch on response — aborting")
                            terminateSession(
                                ExchangeSession.State.ERROR,
                                "Security alert: Peer identity mismatch detected. Exchange aborted.",
                                auditErrorCode = ExchangeAuditEntry.ERR_MITM_DETECTED
                            )
                            return@launch
                        }
                    }
                    is KnownPeerRepository.IdentityKeyResult.NotFound ->
                        knownPeerRepository.upsertIdentityKey(endpointId, peerIdentityKey)
                    is KnownPeerRepository.IdentityKeyResult.Corrupt -> {
                        Timber.e(result.cause, "Corrupt TOFU record for ${result.endpointId} — aborting")
                        terminateSession(
                            ExchangeSession.State.ERROR,
                            "Peer identity record is corrupt. Exchange aborted for your safety.",
                            auditErrorCode = ExchangeAuditEntry.ERR_CRYPTO_ERROR
                        )
                        return@launch
                    }
                }

                sessionPeerKeyHash = CryptoUtils.identityKeyHash(peerIdentityKey)
                challengeVerifiedByEndpoint.add(endpointId)
                Timber.d("Challenge verified for $endpointId — advancing to ECDH")
                sendPublicKey(endpointId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to verify challenge response from $endpointId")
                terminateSession(ExchangeSession.State.ERROR)
            }
        }
    }

    // Post-quantum hybrid KEM key exchange (ML-KEM-768 + X25519, wire protocol v8)

    /**
     * Initiator side: create a new hybrid KEM session and send a KEM HELLO to the peer.
     *
     * The HELLO carries our hybrid public key (1217 bytes encoded) inside a JSON envelope.
     * The peer responds with a HELLO_ACK containing the encapsulated ciphertext, after which
     * both sides can independently derive the same 32-byte shared secret.
     */
    private fun sendPublicKey(endpointId: String) {
        val (session, hello) = hybridKemEngine.initiatorSession(sessionId, HybridKemEngine.MAX_VERSION)

        if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
            val ctx = peerCtxByEndpoint.getOrPut(endpointId) { PeerCtx() }
            ctx.kemSession = session
            if (ctx.handshake == HandshakeState.IDLE) ctx.handshake = HandshakeState.KEY_SENT
        } else {
            kemSession = session
            if (handshakeState == HandshakeState.IDLE) handshakeState = HandshakeState.KEY_SENT
        }

        val payload = byteArrayOf(MSG_TYPE_PUBLIC_KEY) + hello.toJson().toByteArray(Charsets.UTF_8)
        transport.sendBytes(endpointId, payload)
        Timber.d("KEM HELLO sent to $endpointId (v${hello.maxVersion}, session=${sessionId.take(8)})")
    }

    /**
     * Handle an incoming MSG_TYPE_PUBLIC_KEY message.
     *
     * With the hybrid KEM protocol the same message type carries two different payloads:
     * - A JSON `HelloPayload`    (from the initiator — contains the hybrid public key)
     * - A JSON `HelloAckPayload` (from the responder — contains the KEM ciphertext)
     *
     * Which one we expect is determined by our current [handshakeState]:
     * - `IDLE`     → we haven't sent yet, so the peer is the initiator; parse as HELLO.
     * - `KEY_SENT` → we already sent a HELLO, so the peer is responding; parse as HELLO_ACK.
     */
    private fun handleIncomingPublicKey(endpointId: String, data: ByteArray) {
        try {
            val json = String(data, Charsets.UTF_8)

            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
                handleRoomHostKemMessage(endpointId, json)
                return
            }

            // P2P / Room-guest path
            when (handshakeState) {
                HandshakeState.KEY_SENT -> {
                    // We sent HELLO first (we are the initiator). The peer responded with HELLO_ACK.
                    val ack     = HybridKemEngine.HelloAckPayload.fromJson(json)
                    val session = kemSession ?: run {
                        Timber.e("kemSession null in KEY_SENT state — session state corrupt")
                        terminateSession(ExchangeSession.State.ERROR); return
                    }
                    hybridKemEngine.completeInitiatorSession(session, ack)
                    val ss = session.sharedSecret ?: run {
                        Timber.e("KEM shared secret null after completeInitiatorSession")
                        terminateSession(ExchangeSession.State.ERROR); return
                    }
                    sessionKey     = SecretKeySpec(ss, "AES")
                    val (sr, rr)  = initDirectionalRatchets(sessionKey!!, isInitiator = true)
                    sendRatchet    = sr; recvRatchet = rr
                    handshakeState = HandshakeState.COMPLETE
                    Timber.i("Handshake COMPLETE — initiator, PQ-hybrid v${session.negotiatedVersion}, session=${sessionId.take(8)}")
                    showSasAndAwaitConfirmation(endpointId)
                }

                HandshakeState.IDLE -> {
                    // Peer sent HELLO first (peer is the initiator, we are the responder).
                    val hello        = HybridKemEngine.HelloPayload.fromJson(json)
                    val (session, ack) = hybridKemEngine.responderSession(hello)
                    kemSession         = session
                    val ss = session.sharedSecret ?: run {
                        Timber.e("KEM shared secret null after responderSession")
                        terminateSession(ExchangeSession.State.ERROR); return
                    }
                    sessionKey     = SecretKeySpec(ss, "AES")
                    val (sr, rr)  = initDirectionalRatchets(sessionKey!!, isInitiator = false)
                    sendRatchet    = sr; recvRatchet = rr
                    handshakeState = HandshakeState.KEY_RECEIVED

                    val ackPayload = byteArrayOf(MSG_TYPE_PUBLIC_KEY) + ack.toJson().toByteArray(Charsets.UTF_8)
                    transport.sendBytes(endpointId, ackPayload)
                    Timber.d("KEM HELLO_ACK sent to $endpointId (v${session.negotiatedVersion})")

                    handshakeState = HandshakeState.COMPLETE
                    Timber.i("Handshake COMPLETE — responder, PQ-hybrid v${session.negotiatedVersion}, session=${sessionId.take(8)}")
                    showSasAndAwaitConfirmation(endpointId)
                }

                HandshakeState.KEY_RECEIVED, HandshakeState.COMPLETE ->
                    Timber.w("Duplicate KEM message (state=$handshakeState) — ignoring from $endpointId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process KEM message from $endpointId")
            terminateSession(ExchangeSession.State.ERROR)
        }
    }

    /** Room-host variant: each peer in the room has its own [PeerCtx] with its own KEM session. */
    private fun handleRoomHostKemMessage(endpointId: String, json: String) {
        val ctx = peerCtxByEndpoint.getOrPut(endpointId) { PeerCtx() }
        when (ctx.handshake) {
            HandshakeState.KEY_SENT -> {
                // We sent a HELLO to this peer; they replied with HELLO_ACK.
                val ack     = HybridKemEngine.HelloAckPayload.fromJson(json)
                val session = ctx.kemSession ?: run {
                    Timber.e("Room host: kemSession null for $endpointId in KEY_SENT state")
                    terminateSession(ExchangeSession.State.ERROR); return
                }
                hybridKemEngine.completeInitiatorSession(session, ack)
                val ss = session.sharedSecret ?: run {
                    Timber.e("Room host: shared secret null after completion for $endpointId")
                    terminateSession(ExchangeSession.State.ERROR); return
                }
                ctx.sessionKey  = SecretKeySpec(ss, "AES")
                val (sr, rr)   = initDirectionalRatchets(ctx.sessionKey!!, isInitiator = true)
                ctx.sendRatchet = sr; ctx.recvRatchet = rr
                ctx.handshake   = HandshakeState.COMPLETE
                Timber.i("Room host: handshake COMPLETE with $endpointId (initiator, v${session.negotiatedVersion})")
                sendProfile(endpointId)
            }

            HandshakeState.IDLE -> {
                // Peer sent HELLO; we are the responder for this room slot.
                val hello             = HybridKemEngine.HelloPayload.fromJson(json)
                val (session, ack)    = hybridKemEngine.responderSession(hello)
                ctx.kemSession        = session
                val ss = session.sharedSecret ?: run {
                    Timber.e("Room host: shared secret null after responder session for $endpointId")
                    terminateSession(ExchangeSession.State.ERROR); return
                }
                ctx.sessionKey  = SecretKeySpec(ss, "AES")
                val (sr, rr)   = initDirectionalRatchets(ctx.sessionKey!!, isInitiator = false)
                ctx.sendRatchet = sr; ctx.recvRatchet = rr
                ctx.handshake   = HandshakeState.KEY_RECEIVED

                val ackPayload = byteArrayOf(MSG_TYPE_PUBLIC_KEY) + ack.toJson().toByteArray(Charsets.UTF_8)
                transport.sendBytes(endpointId, ackPayload)
                ctx.handshake = HandshakeState.COMPLETE
                Timber.i("Room host: handshake COMPLETE with $endpointId (responder, v${session.negotiatedVersion})")
                sendProfile(endpointId)
            }

            else -> Timber.w("Room host: duplicate KEM message from $endpointId (state=${ctx.handshake})")
        }
    }

    // SAS + ratchet helpers

    private fun initDirectionalRatchets(
        key: javax.crypto.SecretKey,
        isInitiator: Boolean
    ): Pair<DoubleRatchetState, DoubleRatchetState> {
        val sendLabel = if (isInitiator) "aura-init-send" else "aura-init-recv"
        val recvLabel = if (isInitiator) "aura-init-recv" else "aura-init-send"
        val sr = CryptoUtils.newRatchet(CryptoUtils.deriveSubkey(key, sendLabel))
        val rr = CryptoUtils.newRatchet(CryptoUtils.deriveSubkey(key, recvLabel))
        Timber.d("Ratchets initialised (initiator=$isInitiator)")
        return sr to rr
    }

    private fun showSasAndAwaitConfirmation(endpointId: String) {
        val ss = kemSession?.sharedSecret ?: run {
            Timber.e("Cannot display SAS — KEM shared secret unavailable for $endpointId")
            terminateSession(ExchangeSession.State.ERROR); return
        }
        val pin = SasVerifier.deriveFromSharedSecret(ss)
        Timber.i("SAS PIN $pin derived for $endpointId — awaiting UI confirmation")
        pendingSasEndpointId = endpointId
        val current = sessionState.value
        _sessionState.value = current?.copy(state = ExchangeSession.State.VERIFYING, sasPin = pin)
        broadcastState(sessionState.value)
        updateNotification(getString(R.string.status_verifying))
    }

    // Profile exchange

    private fun handleIncomingProfile(endpointId: String, encryptedData: ByteArray) {
        if (encryptedData.size > MAX_PROFILE_PAYLOAD_BYTES) {
            Timber.w("Incoming profile from $endpointId exceeds size limit — rejecting")
            terminateSession(ExchangeSession.State.ERROR)
            return
        }
        updateSessionState(ExchangeSession.State.EXCHANGING)

        if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST) {
            val ctx  = peerCtxByEndpoint[endpointId]
            val rkey = ctx?.sessionKey ?: run {
                Timber.e("No room session key for $endpointId"); return
            }
            scope.launch {
                try {
                    val roomRatchet = ctx?.recvRatchet
                    val decrypted   = if (roomRatchet != null)
                        CryptoUtils.ratchetDecrypt(roomRatchet, encryptedData)
                    else
                        CryptoUtils.decrypt(rkey, encryptedData)
                    val mapType    = object : TypeToken<Map<String, String>>() {}.type
                    val profileMap: Map<String, String> =
                        gson.fromJson(String(decrypted, Charsets.UTF_8), mapType)
                    when (val r = PayloadValidator.validateProfilePayload(profileMap)) {
                        is PayloadValidator.ValidationResult.Ok -> { }
                        else -> { Timber.w("Room-guest profile rejected: $r ($endpointId)"); return@launch }
                    }
                    val cleanMap = profileMap.filterKeys { !it.startsWith("_") }
                    val keyHash  = (knownPeerRepository.getIdentityKey(endpointId)
                        as? KnownPeerRepository.IdentityKeyResult.Found)
                        ?.key?.let { CryptoUtils.identityKeyHash(it) }
                    val contact = Contact.fromMap(
                        id = UUID.randomUUID().toString(), map = cleanMap, endpointId = endpointId
                    ).copy(identityKeyHash = keyHash)
                    contactRepository.saveDeduped(contact)
                    _connectedCount.value = _connectedCount.value + 1
                    Timber.i("Room host saved guest contact: ${contact.displayName} (total=${connectedCount.value})")
                    auditRepository.logSuccess(peerIdentityKeyHash = keyHash, channel = ExchangeAuditEntry.CHANNEL_ROOM_HOST)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to process room-guest profile")
                }
            }
            return
        }

        val key = sessionKey ?: run {
            Timber.e("No session key available — cannot decrypt profile"); return
        }
        scope.launch {
            try {
                val p2pRatchet = recvRatchet
                val decrypted  = if (p2pRatchet != null)
                    CryptoUtils.ratchetDecrypt(p2pRatchet, encryptedData)
                else
                    CryptoUtils.decrypt(key, encryptedData)
                val mapType    = object : TypeToken<Map<String, String>>() {}.type
                val profileMap: Map<String, String> =
                    gson.fromJson(String(decrypted, Charsets.UTF_8), mapType)

                when (val r = PayloadValidator.validateProfilePayload(profileMap)) {
                    is PayloadValidator.ValidationResult.Ok -> { }
                    else -> {
                        Timber.w("Profile payload rejected: $r ($endpointId)")
                        terminateSession(ExchangeSession.State.ERROR, auditErrorCode = ExchangeAuditEntry.ERR_PAYLOAD_INVALID)
                        return@launch
                    }
                }
                val cleanMap = profileMap.filterKeys { !it.startsWith("_") }
                val keyHash  = (knownPeerRepository.getIdentityKey(endpointId)
                    as? KnownPeerRepository.IdentityKeyResult.Found)
                    ?.key?.let { CryptoUtils.identityKeyHash(it) }
                val contact = Contact.fromMap(
                    id = UUID.randomUUID().toString(), map = cleanMap, endpointId = endpointId
                ).copy(identityKeyHash = keyHash)

                val sessionWarning: String? = if (keyHash != null) {
                    when (val rotation = identityRotationDetector.check(contact.displayName, keyHash)) {
                        is IdentityRotationDetector.RotationEvent.KeyRotated ->
                            "Identity key changed for ${contact.displayName}. Verify this is really them (was: …${rotation.storedHash.take(8)})."
                        else -> null
                    }
                } else null

                val mergeEvent: MergeEvent? = contactRepository.saveDeduped(contact)

                val storedVersion       = knownPeerRepository.getLastSeenProfileVersion(endpointId)
                val profileVersionBumped = storedVersion > 0 && contact.profileVersion > storedVersion
                if (contact.profileVersion > 0) {
                    knownPeerRepository.updateLastSeenProfileVersion(endpointId, contact.profileVersion)
                }

                val current = sessionState.value
                _sessionState.value = current?.copy(
                    state               = ExchangeSession.State.COMPLETED,
                    receivedContact     = contact,
                    errorMessage        = sessionWarning,
                    mergeEvent          = mergeEvent,
                    profileVersionBumped = profileVersionBumped
                )
                broadcastState(sessionState.value)
                Timber.i("Exchange complete — saved contact: ${contact.displayName}")
                vibrateShort()

                val auditHash = keyHash ?: sessionPeerKeyHash
                auditRepository.logSuccess(peerIdentityKeyHash = auditHash, channel = sessionChannel)

                timeoutJob?.cancel()
                gestureVerified = false
                scope.launch {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        authPreferences.setGestureGateOpen(false)
                    }
                }
                sessionPeerKeyHash = null
                delay(500)
                stopSelf()
            } catch (e: Exception) {
                Timber.e(e, "Failed to process incoming profile")
                terminateSession(ExchangeSession.State.ERROR)
            }
        }
    }

    private fun sendProfile(endpointId: String) {
        updateSessionState(ExchangeSession.State.EXCHANGING)

        val key: javax.crypto.SecretKey? =
            if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST)
                peerCtxByEndpoint[endpointId]?.sessionKey
            else
                sessionKey

        if (key == null) {
            Timber.e("sendProfile() invoked without sessionKey for $endpointId")
            if (currentMode != ExchangeSession.ExchangeMode.ROOM_HOST) {
                terminateSession(ExchangeSession.State.ERROR)
            }
            return
        }
        scope.launch {
            val profile = profileRepository.get() ?: return@launch
            val stamped = profile.toShareableMap().toMutableMap()
            PayloadValidator.stampOutgoingProfile(stamped)
            val profileJson = gson.toJson(stamped)
            val ratchet: DoubleRatchetState? =
                if (currentMode == ExchangeSession.ExchangeMode.ROOM_HOST)
                    peerCtxByEndpoint[endpointId]?.sendRatchet
                else
                    sendRatchet
            val encrypted = if (ratchet != null)
                CryptoUtils.ratchetEncrypt(ratchet, profileJson.toByteArray(Charsets.UTF_8))
            else
                CryptoUtils.encrypt(key, profileJson.toByteArray(Charsets.UTF_8))
            val payload = byteArrayOf(MSG_TYPE_PROFILE) + encrypted
            transport.sendBytes(endpointId, payload)
            Timber.d("Encrypted profile sent to $endpointId")
            sendAvatarIfPresent(endpointId, profile.avatarUri)
        }
    }

    // Avatar streaming — gms (NearbyConnectionsTransport) only

    /**
     * Send the user's avatar as a STREAM payload.
     *
     * Avatar streaming uses Nearby Connections STREAM payloads which are not part
     * of the [NearbyTransport] interface. This is skipped on the `foss` flavor
     * (Wi-Fi Direct is bytes-only). Both sides handle avatar absence gracefully —
     * the contact is saved without an avatar and can be updated at a later exchange.
     */
    private fun sendAvatarIfPresent(endpointId: String, avatarPath: String) {
        if (avatarPath.isBlank()) return

        if (avatarPath.startsWith("content://")) {
            try {
                val pfd = contentResolver.openFileDescriptor(
                    android.net.Uri.parse(avatarPath), "r"
                ) ?: run {
                    Timber.w("Could not open content URI for avatar: $avatarPath"); return
                }
                if (pfd.statSize > MAX_AVATAR_BYTES) {
                    Timber.w("content:// avatar exceeds size limit (${pfd.statSize}B) — skipping")
                    pfd.close(); return
                }
                transport.sendBytes(endpointId, byteArrayOf(MSG_TYPE_AVATAR))
                val sent = transport.sendAvatarStream(
                    endpointId,
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd),
                    pfd.statSize,
                )
                if (!sent) Timber.d("Avatar streaming skipped — transport does not support STREAM payloads")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send avatar via content URI")
            }
            return
        }

        val file = java.io.File(avatarPath)
        if (!file.exists() || file.length() <= 0L) {
            Timber.w("Avatar path set but file missing/empty: $avatarPath"); return
        }
        if (file.length() > MAX_AVATAR_BYTES) {
            Timber.w("Avatar exceeds ${MAX_AVATAR_BYTES}B — skipping"); return
        }
        try {
            transport.sendBytes(endpointId, byteArrayOf(MSG_TYPE_AVATAR))
            val sent = transport.sendAvatarStream(
                endpointId,
                file.inputStream(),
                file.length(),
            )
            if (sent) Timber.d("Avatar stream sent (${file.length()}B) to $endpointId")
            else Timber.d("Avatar streaming skipped — transport does not support STREAM payloads")
        } catch (e: Exception) {
            Timber.e(e, "Failed to open avatar for stream send")
        }
    }

    private fun handleIncomingAvatarStream(
        endpointId: String,
        inputStream: java.io.InputStream,
    ) {
        if (!awaitingAvatarStream.contains(endpointId)) {
            Timber.w("STREAM from $endpointId without preceding MSG_TYPE_AVATAR — dropping")
            return
        }
        scope.launch {
            val safeEp = endpointId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(16)
            val tmp = java.io.File.createTempFile("avatar-incoming-${safeEp}-", ".jpg", cacheDir)
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
                                tmp.delete(); awaitingAvatarStream.remove(endpointId); return@launch
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
                val jpegMagic      = ByteArray(3)
                val jpegBytesRead  = tmp.inputStream().use { it.read(jpegMagic, 0, 3) }
                if (jpegBytesRead < 3 ||
                    jpegMagic[0] != 0xFF.toByte() ||
                    jpegMagic[1] != 0xD8.toByte() ||
                    jpegMagic[2] != 0xFF.toByte()
                ) {
                    Timber.w("Avatar from $endpointId failed JPEG magic check — discarding")
                    tmp.delete(); awaitingAvatarStream.remove(endpointId); return@launch
                }
                val contact = contactRepository.findLatestByEndpoint(endpointId)
                if (contact == null) {
                    Timber.w("No saved contact yet for $endpointId; discarding avatar")
                    tmp.delete(); return@launch
                }
                val dest = com.showerideas.aura.utils.AvatarUtils.peerAvatarFile(
                    this@NearbyExchangeService, contact.id
                )
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
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

    private fun cleanupPartialAvatarFiles(endpointId: String) {
        try {
            awaitingAvatarStream.remove(endpointId)
            val dir    = cacheDir ?: return
            val safeEp = endpointId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(16)
            dir.listFiles { f ->
                f.name.startsWith("avatar-incoming-${safeEp}-") && f.name.endsWith(".jpg")
            }?.forEach { stale ->
                val age = System.currentTimeMillis() - stale.lastModified()
                if (age > 5_000L && stale.delete()) {
                    Timber.d("Removed partial avatar tmp: ${stale.name} (age=${age}ms)")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Partial avatar cleanup failed for $endpointId")
        }
    }

    // Helpers

    private fun decodeEC256PublicKey(encoded: ByteArray): PublicKey {
        val spec  = java.security.spec.X509EncodedKeySpec(encoded)
        val key   = java.security.KeyFactory.getInstance("EC").generatePublic(spec)
        val ecKey = key as? java.security.interfaces.ECPublicKey
            ?: throw java.security.GeneralSecurityException("Incoming key is not an EC key — rejected")
        val secp256r1Order = java.math.BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16
        )
        if (ecKey.params.order != secp256r1Order) {
            throw java.security.GeneralSecurityException(
                "EC key is not on secp256r1 — curve-downgrade attempt rejected"
            )
        }
        return key
    }

    private fun updateSessionState(state: ExchangeSession.State, errorMessage: String? = null) {
        val current = sessionState.value
        _sessionState.value = current?.copy(state = state, errorMessage = errorMessage)
        broadcastState(sessionState.value)
        val statusText = when (state) {
            ExchangeSession.State.ADVERTISING -> getString(R.string.status_advertising)
            ExchangeSession.State.DISCOVERING -> getString(R.string.status_discovering)
            ExchangeSession.State.CONNECTING  -> getString(R.string.status_connecting)
            ExchangeSession.State.VERIFYING   -> getString(R.string.status_verifying)
            ExchangeSession.State.EXCHANGING  -> getString(R.string.status_exchanging)
            ExchangeSession.State.COMPLETED   -> getString(R.string.exchange_completed,
                current?.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.someone))
            ExchangeSession.State.CANCELLED   -> getString(R.string.exchange_cancelled)
            ExchangeSession.State.ERROR       -> getString(R.string.exchange_error_generic)
        }
        updateNotification(statusText)
    }

    private fun broadcastState(session: ExchangeSession?) {
        sendBroadcast(Intent(ACTION_STATE_UPDATE).apply {
            `package` = packageName
            putExtra(EXTRA_STATE, session?.state?.name)
        })
    }

    private fun createNotificationChannel() {
        // delegate to centralized hardened channel registry
        NotificationChannels.ensureChannels(this)
    }

    private fun buildNotification(status: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_aura_small)
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
