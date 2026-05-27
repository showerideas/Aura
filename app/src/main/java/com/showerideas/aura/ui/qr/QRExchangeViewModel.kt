package com.showerideas.aura.ui.qr

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.showerideas.aura.BuildConfig
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.MergeEvent
import com.showerideas.aura.network.RelayClient
import com.showerideas.aura.utils.CryptoUtils
import com.showerideas.aura.utils.SasVerifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.security.KeyPair
import java.security.PublicKey
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * Owns the QR exchange state that must survive configuration changes.
 *
 *  - One ephemeral ECDH keypair per 60-second QR cycle (re-rolled on expiry).
 *  - The rendered QR bitmap and countdown timer.
 *  - The full relay exchange flow:
 *      1. Parse peer QR -> ECDH -> AES-256 session key.
 *      2. Encrypt local profile -> POST to our relay slot.
 *      3. Poll peer's relay slot -> decrypt -> hold in memory.
 *      4. Derive SAS from both ephemeral public keys -- show for MITM check.
 *      5. User confirms SAS match -> save contact to Room.
 *
 * SAS verification on QR path
 * The Nearby path shows a SAS dialog mid-exchange (State.VERIFYING). The QR
 * path mirrors this: after decrypting the peer's profile but before saving it
 * to Room, [PairingResult.AwaitingSasConfirmation] is emitted. The Fragment
 * shows an AlertDialog; the user verbally compares the 6-digit code with their
 * peer. Confirming saves the contact; a mismatch discards the pending contact
 * without persisting anything -- protecting against relay-based MITM attacks.
 */
@HiltViewModel
class QRExchangeViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val contactRepository: ContactRepository,
    private val relayClient: RelayClient
) : ViewModel() {

    companion object {
        const val QR_EXPIRY_MS               = 60_000L
        const val QR_PAYLOAD_VERSION         = 1
        private const val QR_SIZE_PX         = 800
        private const val RELAY_POLL_TIMEOUT_MS  = 30_000L
        private const val RELAY_POLL_INTERVAL_MS = 2_000L
    }

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _secondsRemaining = MutableStateFlow((QR_EXPIRY_MS / 1000).toInt())
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    sealed class PairingResult {
        /**
         * Full exchange succeeded — [contact] has been persisted to Room.
         * [mergeEvent] is non-null when the contact was a returning peer whose
         * visible fields changed; the Fragment should show [ContactMergeBottomSheet]
         * ().
         */
        data class Success(val contact: Contact, val mergeEvent: MergeEvent? = null) : PairingResult()
        /**
         * Peer profile received and decrypted. The Fragment should show a SAS
         * dialog displaying [sasPin]; call [confirmSas] or [abortSas] to resolve.
         * The contact is NOT saved until [confirmSas] is called.
         */
        data class AwaitingSasConfirmation(val sasPin: String) : PairingResult()
        /** Peer QR payload was malformed or missing required fields. */
        object Invalid : PairingResult()
        /** Peer QR timestamp is older than [QR_EXPIRY_MS]. */
        object Expired : PairingResult()
        /** Local keypair was cleared before the scan completed (should not happen). */
        object MissingLocalKey : PairingResult()
        /** Peer never posted their profile to the relay within the poll window. */
        object RelayTimeout : PairingResult()
        /** Any unexpected crypto or network error. */
        data class Error(val message: String) : PairingResult()
    }

    private val _pairingResult = MutableStateFlow<PairingResult?>(null)
    val pairingResult: StateFlow<PairingResult?> = _pairingResult.asStateFlow()

    @Volatile private var ourKeyPair: KeyPair? = null
    @Volatile private var localEndpoint: String = UUID.randomUUID().toString()

    /**
     * Decrypted peer contact held between [AwaitingSasConfirmation] emission
     * and [confirmSas]. Cleared on [abortSas] or [onCleared].
     */
    @Volatile private var pendingContact: Contact? = null

    private var countdownJob: Job? = null

    init { regenerateAndRender() }

    /** Rotate keypair + endpoint + QR bitmap, then (re)start the 60 s countdown. */
    private fun regenerateAndRender() {
        val kp = CryptoUtils.generateEphemeralECDHKeyPair()
        ourKeyPair = kp
        localEndpoint = UUID.randomUUID().toString()
        _qrBitmap.value = renderQrBitmap(kp.public, localEndpoint)
        startCountdown()
    }

    private fun renderQrBitmap(publicKey: PublicKey, endpoint: String): Bitmap? {
        val payload = JSONObject().apply {
            put("v", QR_PAYLOAD_VERSION)
            put("pubkey", Base64.getEncoder().encodeToString(publicKey.encoded))
            put("endpoint", endpoint)
            put("ts", System.currentTimeMillis())
        }.toString()
        return try {
            val bitMatrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX)
            BarcodeEncoder().createBitmap(bitMatrix)
        } catch (e: WriterException) {
            Timber.e(e, "Failed to render QR bitmap")
            null
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = (QR_EXPIRY_MS / 1000).toInt()
            _secondsRemaining.value = remaining
            while (isActive && remaining > 0) {
                delay(1_000L)
                remaining -= 1
                _secondsRemaining.value = remaining.coerceAtLeast(0)
            }
            if (isActive) regenerateAndRender()
        }
    }

    /**
     * Called when the ZXing scanner returns a result. Kicks off the full relay
     * exchange on a coroutine -- UI should show a loading indicator while
     * [pairingResult] is null after this call.
     */
    fun onPeerScanned(peerJson: String) {
        viewModelScope.launch {
            _pairingResult.value = pairWithPeer(peerJson)
        }
    }

    private suspend fun pairWithPeer(rawJson: String): PairingResult {
        return try {
            // Step 1: parse the peer's QR payload
            val obj = JSONObject(rawJson)
            val peerPubKeyB64 = obj.optString("pubkey")
            val peerEndpoint  = obj.optString("endpoint")
            val ts            = obj.optLong("ts", 0L)

            if (peerPubKeyB64.isEmpty() || peerEndpoint.isEmpty()) return PairingResult.Invalid
            if (ts > 0 && System.currentTimeMillis() - ts > QR_EXPIRY_MS) return PairingResult.Expired

            // Step 2: ECDH -> AES-256 session key
            val ourKp        = ourKeyPair ?: return PairingResult.MissingLocalKey
            val peerKeyBytes = Base64.getDecoder().decode(peerPubKeyB64)
            val peerPubKey   = decodeEC256PublicKey(peerKeyBytes)
            val sharedKey: SecretKey = CryptoUtils.deriveSharedAESKey(ourKp.private, peerPubKey)
            Timber.i("QR ECDH -- AES-256 derived (peer endpoint=%s)", peerEndpoint)

            // Step 3: serialize + encrypt local profile
            val profile       = profileRepository.getOrCreate()
            val profileJson   = JSONObject(profile.toShareableMap()).toString()
            val encryptedOurs = CryptoUtils.encrypt(
                sharedKey,
                profileJson.toByteArray(Charsets.UTF_8)
            )

            val baseUrl = BuildConfig.RELAY_BASE_URL

            // Steps 4+5: POST ours + poll for theirs (IO thread)
            withContext(Dispatchers.IO) {
                // POST in background so the peer's poll can start immediately.
                launch {
                    val ok = relayClient.postSlot(baseUrl, localEndpoint, encryptedOurs)
                    if (!ok) Timber.w("QR relay POST to slot/%s failed", localEndpoint)
                }
                // Poll for peer's profile; decrypt + hold (do NOT save yet).
                pollAndDecrypt(baseUrl, peerEndpoint, sharedKey, ourKp.public, peerPubKey)
            } ?: PairingResult.RelayTimeout

        } catch (e: Exception) {
            Timber.e(e, "QR exchange error")
            PairingResult.Error(e.message ?: "unknown error")
        }
    }

    /**
     * Polls the relay at [peerEndpoint] until encrypted bytes arrive or the
     * [RELAY_POLL_TIMEOUT_MS] window closes. On receipt, decrypts the contact,
     * holds it in [pendingContact], derives the SAS, and returns
     * [PairingResult.AwaitingSasConfirmation] -- the contact is NOT persisted yet.
     *
     * Must be called from [Dispatchers.IO].
     */
    private suspend fun pollAndDecrypt(
        baseUrl: String,
        peerEndpoint: String,
        sharedKey: SecretKey,
        ourPubKey: PublicKey,
        peerPubKey: PublicKey
    ): PairingResult? {
        val deadline = System.currentTimeMillis() + RELAY_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val bytes = relayClient.getSlot(baseUrl, peerEndpoint)
            if (bytes != null && bytes.isNotEmpty()) {
                return decryptAndHold(bytes, sharedKey, peerEndpoint, ourPubKey, peerPubKey)
            }
            delay(RELAY_POLL_INTERVAL_MS)
        }
        Timber.w("QR relay poll timed out for peer endpoint=%s", peerEndpoint)
        return null
    }

    /**
     * Decrypts [encryptedBytes] with [sharedKey], parses the contact, stores it in
     * [pendingContact], derives the SAS from both ephemeral public keys, and returns
     * [PairingResult.AwaitingSasConfirmation].
     *
     * The contact is intentionally NOT saved to Room here -- [confirmSas] does that
     * once the user verifies the SAS matches the peer's display.
     */
    private fun decryptAndHold(
        encryptedBytes: ByteArray,
        sharedKey: SecretKey,
        peerEndpoint: String,
        ourPubKey: PublicKey,
        peerPubKey: PublicKey
    ): PairingResult {
        return try {
            val plaintext = CryptoUtils.decrypt(sharedKey, encryptedBytes)
            val peerObj   = JSONObject(plaintext.toString(Charsets.UTF_8))
            val peerMap   = buildMap<String, String> {
                peerObj.keys().forEach { key -> put(key, peerObj.optString(key)) }
            }
            val contact = Contact.fromMap(
                id         = UUID.randomUUID().toString(),
                map        = peerMap,
                endpointId = peerEndpoint
            )
            // Hold in memory -- only persisted after the user confirms the SAS.
            pendingContact = contact

            val sasPin = SasVerifier.derive(ourPubKey, peerPubKey)
            Timber.i("QR SAS derived: %s -- awaiting user confirmation", sasPin)
            PairingResult.AwaitingSasConfirmation(sasPin)
        } catch (e: Exception) {
            Timber.e(e, "QR decrypt/hold failed")
            PairingResult.Error(e.message ?: "decrypt failed")
        }
    }

    /**
     * Called by the Fragment when the user confirms the SAS matches their peer's display.
     * Persists [pendingContact] to Room and emits [PairingResult.Success].
     */
    fun confirmSas() {
        viewModelScope.launch {
            val contact = pendingContact
            if (contact == null) {
                Timber.w("confirmSas called but pendingContact is null -- ignoring")
                return@launch
            }
            pendingContact = null
            try {
                // saveDeduped returns a MergeEvent when the peer is a returning contact
                // with changed fields — pass it to the Fragment so it can show the merge UI.
                val mergeEvent = contactRepository.saveDeduped(contact)
                Timber.i("QR exchange complete (SAS confirmed) -- contact saved: %s", contact.displayName)
                _pairingResult.value = PairingResult.Success(contact, mergeEvent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save contact after SAS confirmation")
                _pairingResult.value = PairingResult.Error(e.message ?: "save failed")
            }
        }
    }

    /**
     * Called by the Fragment when the user reports the SAS does not match their peer.
     * Discards [pendingContact] without saving and emits [PairingResult.Error].
     *
     * A MITM who substitutes their own ephemeral key produces a different SAS --
     * aborting here ensures no attacker-controlled contact data is persisted.
     */
    fun abortSas() {
        pendingContact = null
        Timber.w("QR SAS mismatch reported -- exchange aborted, pendingContact discarded")
        _pairingResult.value = PairingResult.Error("SAS mismatch -- possible MITM detected")
    }

    fun consumePairingResult() { _pairingResult.value = null }

    /**
     * Apply per-field [selections] chosen by the user in the merge bottom sheet.
     * Called from [QRExchangeFragment] after the user reviews a [PairingResult.Success]
     * that included a non-null [MergeEvent].
     */
    fun applyMergeSelections(base: Contact, selections: Map<String, String>) {
        viewModelScope.launch {
            contactRepository.applyMergeSelections(base, selections)
        }
    }

    private fun decodeEC256PublicKey(encoded: ByteArray): PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        return java.security.KeyFactory.getInstance("EC").generatePublic(spec)
    }

    override fun onCleared() {
        countdownJob?.cancel()
        countdownJob = null
        pendingContact = null
        super.onCleared()
    }
}
