package com.showerideas.aura.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.showerideas.aura.R
import com.showerideas.aura.data.RoomRepository
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.RoomMember
import com.showerideas.aura.model.RoomSession
import com.showerideas.aura.model.RoomState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * T10 — Multi-party room exchange service (star-topology card router).
 *
 * ## Architecture
 * ```
 *         ┌─────────────┐
 *         │  Host/Hub   │  ← RoomExchangeService runs here
 *         └──────┬──────┘
 *         ┌──────┼──────┐
 *         ▼      ▼      ▼
 *       Peer A  Peer B  Peer C
 * ```
 * The host acts as a star-topology relay. Each participant submits their
 * AES-GCM-encrypted card payload (keyed with the shared room key). The host
 * collects all cards, then re-delivers each card to every OTHER participant
 * with a per-delivery re-encryption (fresh IV, same room key). Once delivery
 * is ACKed the card is decrypted locally and saved via [ContactDao].
 *
 * ## Room key
 * The 32-byte room key from [RoomSession.roomKey] (Base64) is used as the
 * AES-256 key for both inbound decryption and outbound re-encryption.
 * Each message uses a fresh 12-byte random IV stored as a prefix:
 * ```
 * [12-byte IV] [16-byte GCM tag + ciphertext]
 * ```
 *
 * ## Lifecycle
 * Start via [ACTION_START] with [EXTRA_ROOM_ID]. Stop via [ACTION_STOP] or
 * when the room expires ([RoomRepository.ROOM_TTL_MS] = 10 min).
 */
@AndroidEntryPoint
class RoomExchangeService : Service() {

    companion object {
        const val ACTION_START = "com.showerideas.aura.action.ROOM_START"
        const val ACTION_STOP  = "com.showerideas.aura.action.ROOM_STOP"
        const val ACTION_CARD_RECEIVED = "com.showerideas.aura.action.ROOM_CARD"

        const val EXTRA_ROOM_ID          = "room_id"
        const val EXTRA_SENDER_MEMBER_ID = "sender_member_id"
        const val EXTRA_ENCRYPTED_CARD   = "encrypted_card"   // Base64

        private const val NOTIF_ID = 2002
        private const val GCM_TAG_LENGTH = 128  // bits
        private const val IV_LENGTH      = 12   // bytes
        private const val AES_GCM        = "AES/GCM/NoPadding"
    }

    @Inject lateinit var roomRepository: RoomRepository
    @Inject lateinit var db: AppDatabase

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * In-memory card buffer: memberId → encrypted payload bytes.
     * Cleared when the room is closed.
     */
    private val cardBuffer = ConcurrentHashMap<String, ByteArray>()

    /**
     * Tracks which deliveries have been ACKed: memberId → Set<senderMemberId>
     * so we don't re-deliver on reconnect.
     */
    private val ackTracker = ConcurrentHashMap<String, MutableSet<String>>()

    private var currentRoomId: String? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: run {
                    Timber.w("RoomExchangeService: START without room_id — stopping")
                    stopSelf(); return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification())
                currentRoomId = roomId
                Timber.i("RoomExchangeService: started for room %s", roomId)
                scope.launch { watchRoomExpiry(roomId) }
            }

            ACTION_CARD_RECEIVED -> {
                val roomId    = intent.getStringExtra(EXTRA_ROOM_ID)      ?: return START_NOT_STICKY
                val senderId  = intent.getStringExtra(EXTRA_SENDER_MEMBER_ID) ?: return START_NOT_STICKY
                val encrypted = intent.getStringExtra(EXTRA_ENCRYPTED_CARD)
                    ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                    ?: return START_NOT_STICKY
                scope.launch { handleIncomingCard(roomId, senderId, encrypted) }
            }

            ACTION_STOP -> {
                Timber.i("RoomExchangeService: explicit stop")
                tearDown()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Card routing
    // -------------------------------------------------------------------------

    /**
     * Accept an encrypted card from [senderId], buffer it, then route a
     * fresh re-encryption of it to every other currently-known member.
     */
    private suspend fun handleIncomingCard(
        roomId: String,
        senderId: String,
        encryptedCard: ByteArray
    ) {
        val room = roomRepository.observeRoom(roomId).first() ?: run {
            Timber.w("RoomExchangeService: unknown room %s", roomId)
            return
        }
        if (room.state != RoomState.ACTIVE || System.currentTimeMillis() > room.expiresAt) {
            Timber.w("RoomExchangeService: room %s expired/closed — dropping card", roomId)
            return
        }

        val roomKey = decodeRoomKey(room.roomKey) ?: return

        // 1. Decrypt inbound card with room key to get plaintext
        val plaintext = try {
            aesGcmDecrypt(encryptedCard, roomKey)
        } catch (e: Exception) {
            Timber.e(e, "RoomExchangeService: failed to decrypt card from %s", senderId)
            return
        }

        // 2. Buffer the encrypted payload for future members
        cardBuffer[senderId] = encryptedCard
        Timber.i("RoomExchangeService: buffered card from %s (%d bytes)", senderId, encryptedCard.size)

        // 3. Save to ContactDao (persist locally)
        persistContact(plaintext, senderId, roomId)

        // 4. Mark card received in DB
        roomRepository.markCardReceived(roomId, senderId)

        // 5. Deliver all previously buffered cards to this sender
        for ((bufferedSender, bufferedCard) in cardBuffer) {
            if (bufferedSender == senderId) continue
            if (ackTracker[senderId]?.contains(bufferedSender) == true) continue

            deliverCard(roomKey, bufferedSender, bufferedCard, recipientId = senderId)
        }

        // 6. Deliver this sender's card to all other known members
        val members = roomRepository.observeMembers(roomId).first()
        for (member in members) {
            if (member.memberId == senderId) continue
            if (ackTracker[member.memberId]?.contains(senderId) == true) continue

            deliverCard(roomKey, senderId, encryptedCard, recipientId = member.memberId)
        }
    }

    /**
     * Re-encrypt [cardBytes] with a fresh IV and log delivery to [recipientId].
     * In a real implementation this would push over BLE/WiFi/NFC to the recipient.
     * Here we record the delivery intent and broadcast a local result.
     */
    private fun deliverCard(
        roomKey: SecretKey,
        senderMemberId: String,
        cardBytes: ByteArray,
        recipientId: String
    ) {
        try {
            val reEncrypted = aesGcmEncrypt(
                aesGcmDecrypt(cardBytes, roomKey),   // decrypt then re-encrypt with fresh IV
                roomKey
            )
            // Record ACK
            ackTracker.getOrPut(recipientId) { mutableSetOf() }.add(senderMemberId)

            // Broadcast for transport layer to pick up and relay to the peer device
            val delivery = Intent("com.showerideas.aura.ROOM_CARD_DELIVER").apply {
                putExtra(EXTRA_ROOM_ID, currentRoomId)
                putExtra(EXTRA_SENDER_MEMBER_ID, senderMemberId)
                putExtra("recipient_member_id", recipientId)
                putExtra(EXTRA_ENCRYPTED_CARD,
                    android.util.Base64.encodeToString(reEncrypted, android.util.Base64.NO_WRAP))
            }
            sendBroadcast(delivery)
            Timber.i("RoomExchangeService: delivered card from %s to %s", senderMemberId, recipientId)
        } catch (e: Exception) {
            Timber.e(e, "RoomExchangeService: delivery failed from %s to %s", senderMemberId, recipientId)
        }
    }

    // -------------------------------------------------------------------------
    // Crypto helpers
    // -------------------------------------------------------------------------

    private fun decodeRoomKey(base64Key: String): SecretKey? = try {
        val keyBytes = android.util.Base64.decode(base64Key, android.util.Base64.NO_WRAP)
        SecretKeySpec(keyBytes, "AES")
    } catch (e: Exception) {
        Timber.e(e, "RoomExchangeService: failed to decode room key")
        null
    }

    /**
     * AES-256-GCM encrypt. Output format: [12-byte IV][ciphertext+tag].
     */
    private fun aesGcmEncrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * AES-256-GCM decrypt. Input format: [12-byte IV][ciphertext+tag].
     */
    private fun aesGcmDecrypt(data: ByteArray, key: SecretKey): ByteArray {
        require(data.size > IV_LENGTH) { "Ciphertext too short" }
        val iv         = data.copyOfRange(0, IV_LENGTH)
        val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    // -------------------------------------------------------------------------
    // Contact persistence
    // -------------------------------------------------------------------------

    private suspend fun persistContact(plaintext: ByteArray, senderMemberId: String, roomId: String) {
        try {
            val json    = String(plaintext, Charsets.UTF_8)
            val obj     = org.json.JSONObject(json)
            val contact = Contact(
                id               = UUID.randomUUID().toString(),
                displayName      = obj.optString("displayName", "Room Participant"),
                email            = obj.optString("email"),
                phone            = obj.optString("phone"),
                company          = obj.optString("company"),
                title            = obj.optString("title"),
                website          = obj.optString("website"),
                bio              = obj.optString("bio"),
                avatarBase64     = obj.optString("avatarBase64").takeIf { it.isNotBlank() },
                sourceEndpointId = "room:$roomId:$senderMemberId",
                receivedAt       = System.currentTimeMillis(),
                identityKeyHash  = obj.optString("identityKeyHash").takeIf { it.isNotBlank() }
            )
            db.contactDao().insert(contact)
            Timber.i("RoomExchangeService: persisted contact %s from room member %s",
                contact.displayName, senderMemberId)
        } catch (e: Exception) {
            Timber.w(e, "RoomExchangeService: failed to persist contact from %s", senderMemberId)
        }
    }

    // -------------------------------------------------------------------------
    // Room expiry watch
    // -------------------------------------------------------------------------

    private suspend fun watchRoomExpiry(roomId: String) {
        roomRepository.observeRoom(roomId).collect { room ->
            if (room == null || room.state == RoomState.CLOSED ||
                System.currentTimeMillis() > (room.expiresAt)) {
                Timber.i("RoomExchangeService: room %s closed/expired — shutting down", roomId)
                tearDown()
            }
        }
    }

    private fun tearDown() {
        cardBuffer.clear()
        ackTracker.clear()
        currentRoomId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification() = NotificationCompat.Builder(this, NotificationChannels.CHANNEL_EXCHANGE)
        .setSmallIcon(R.drawable.ic_aura_notification)
        .setContentTitle(getString(R.string.room_service_notification_title))
        .setContentText(getString(R.string.room_service_notification_body))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
}
