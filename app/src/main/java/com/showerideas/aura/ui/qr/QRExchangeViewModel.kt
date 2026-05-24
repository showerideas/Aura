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
import com.showerideas.aura.network.RelayClient
import com.showerideas.aura.utils.CryptoUtils
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
 *      1. Parse peer QR → ECDH → AES-256 session key.
 *      2. Encrypt local profile → POST to our relay slot.
 *      3. Poll peer's relay slot → decrypt → save as Contact.
 */
@HiltViewModel
class QRExchangeViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val contactRepository: ContactRepository,
    private val relayClient: RelayClient
) : ViewModel() {

    companion object {
        const val QR_EXPIRY_MS              = 60_000L
        const val QR_PAYLOAD_VERSION        = 1
        private const val QR_SIZE_PX        = 800
        private const val RELAY_POLL_TIMEOUT_MS  = 30_000L
        private const val RELAY_POLL_INTERVAL_MS = 2_000L
    }

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _secondsRemaining = MutableStateFlow((QR_EXPIRY_MS / 1000).toInt())
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    sealed class PairingResult {
        /** Full exchange succeeded — [contact] has been persisted to Room. */
        data class Success(val contact: Contact) : PairingResult()
        /** Peer QR payload was malformed or missing required fields. */
        object Invalid : PairingResult()
        /** Peer QR timestamp is older than [QR_EXPIRY_MS]. */
        object Expired : PairingResult()
        /** Local keypair was cleared before the scan completed (shouldn't happen). */
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
     * exchange on a coroutine — UI should show a loading indicator while
     * [pairingResult] is null after this call.
     */
    fun onPeerScanned(peerJson: String) {
        viewModelScope.launch {
            _pairingResult.value = pairWithPeer(peerJson)
        }
    }

    private suspend fun pairWithPeer(rawJson: String): PairingResult {
        return try {
            // ── Step 1: parse the peer's QR payload ──────────────────────────
            val obj = JSONObject(rawJson)
            val peerPubKeyB64 = obj.optString("pubkey")
            val peerEndpoint  = obj.optString("endpoint")
            val ts            = obj.optLong("ts", 0L)

            if (peerPubKeyB64.isEmpty() || peerEndpoint.isEmpty()) return PairingResult.Invalid
            if (ts > 0 && System.currentTimeMillis() - ts > QR_EXPIRY_MS) return PairingResult.Expired

            // ── Step 2: ECDH → AES-256 session key ───────────────────────────
            val ourPriv      = ourKeyPair?.private ?: return PairingResult.MissingLocalKey
            val peerKeyBytes = Base64.getDecoder().decode(peerPubKeyB64)
            val peerPubKey   = decodeEC256PublicKey(peerKeyBytes)
            val sharedKey: SecretKey = CryptoUtils.deriveSharedAESKey(ourPriv, peerPubKey)
            Timber.i("QR ECDH — AES-256 derived (peer endpoint=%s)", peerEndpoint)

            // ── Step 3: serialize + encrypt local profile ─────────────────────
            val profile      = profileRepository.getOrCreate()
            val profileJson  = JSONObject(profile.toShareableMap()).toString()
            val encryptedOurs = CryptoUtils.encrypt(
                sharedKey,
                profileJson.toByteArray(Charsets.UTF_8)
            )

            val baseUrl = BuildConfig.RELAY_BASE_URL

            // ── Steps 4 + 5: POST ours + poll for theirs (IO thread) ──────────
            withContext(Dispatchers.IO) {
                // POST our encrypted profile to our own slot in the background.
                // We don't block the poll on its completion — both operations
                // race independently so the peer's poll can start immediately.
                launch {
                    val ok = relayClient.postSlot(baseUrl, localEndpoint, encryptedOurs)
                    if (!ok) Timber.w("QR relay POST to slot/%s failed — peer may miss our profile", localEndpoint)
                }

                // Poll for the peer's profile; decrypt + save on success.
                pollForSlot(baseUrl, peerEndpoint, sharedKey)
            } ?: PairingResult.RelayTimeout

        } catch (e: Exception) {
            Timber.e(e, "QR exchange error")
            PairingResult.Error(e.message ?: "unknown error")
        }
    }

    /**
     * Polls the relay at [peerEndpoint] until encrypted bytes arrive or the
     * [RELAY_POLL_TIMEOUT_MS] window closes. Returns a [PairingResult] on
     * receipt (decrypt + save) or null on timeout.
     *
     * Must be called from [Dispatchers.IO].
     */
    private suspend fun pollForSlot(
        baseUrl: String,
        peerEndpoint: String,
        sharedKey: SecretKey
    ): PairingResult? {
        val deadline = System.currentTimeMillis() + RELAY_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val bytes = relayClient.getSlot(baseUrl, peerEndpoint)
            if (!bytes.isNullOrEmpty()) {
                return decryptAndSave(bytes!!, sharedKey, peerEndpoint)
            }
            delay(RELAY_POLL_INTERVAL_MS)
        }
        Timber.w("QR relay poll timed out for peer endpoint=%s", peerEndpoint)
        return null
    }

    /** Decrypt [encryptedBytes] with [sharedKey], parse the profile, persist as Contact. */
    private suspend fun decryptAndSave(
        encryptedBytes: ByteArray,
        sharedKey: SecretKey,
        peerEndpoint: String
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
            contactRepository.saveDeduped(contact)
            Timber.i("QR exchange complete — contact saved: %s", contact.displayName)
            PairingResult.Success(contact)
        } catch (e: Exception) {
            Timber.e(e, "QR decrypt/save failed")
            PairingResult.Error(e.message ?: "decrypt failed")
        }
    }

    fun consumePairingResult() { _pairingResult.value = null }

    private fun decodeEC256PublicKey(encoded: ByteArray): PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        return java.security.KeyFactory.getInstance("EC").generatePublic(spec)
    }

    override fun onCleared() {
        countdownJob?.cancel()
        countdownJob = null
        super.onCleared()
    }
}

