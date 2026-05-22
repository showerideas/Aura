package com.showerideas.aura.ui.qr

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.showerideas.aura.utils.CryptoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import org.json.JSONObject
import timber.log.Timber
import java.security.KeyPair
import java.security.PublicKey
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * FIX-D: hoist the QR fragment's keypair + countdown out of the Fragment so
 * a configuration change (most commonly screen rotation) does NOT regenerate
 * the keypair, which would invalidate any QR the peer is currently scanning.
 *
 * Owns:
 *  - One ephemeral ECDH keypair per "QR generation" cycle (re-rolled every 60s).
 *  - The current rendered QR bitmap.
 *  - The seconds-remaining countdown that drives the on-screen timer.
 *  - The peer-scan handler that derives the shared AES session key.
 */
@HiltViewModel
class QRExchangeViewModel @Inject constructor() : ViewModel() {

    companion object {
        const val QR_EXPIRY_MS = 60_000L
        const val QR_PAYLOAD_VERSION = 1
        private const val QR_SIZE_PX = 800
    }

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _secondsRemaining = MutableStateFlow((QR_EXPIRY_MS / 1000).toInt())
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    sealed class PairingResult {
        data class Success(val peerEndpoint: String) : PairingResult()
        object Invalid : PairingResult()
        object Expired : PairingResult()
        object MissingLocalKey : PairingResult()
        data class Error(val message: String) : PairingResult()
    }

    private val _pairingResult = MutableStateFlow<PairingResult?>(null)
    val pairingResult: StateFlow<PairingResult?> = _pairingResult.asStateFlow()

    @Volatile
    private var ourKeyPair: KeyPair? = null
    @Volatile
    private var localEndpoint: String = UUID.randomUUID().toString()

    private var countdownJob: Job? = null

    init {
        regenerateAndRender()
    }

    /** Rotate keypair + endpoint + QR bitmap, then (re)start the 60s countdown. */
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
            Timber.e(e, "Failed to render QR")
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
     * Parse a payload scanned from the peer's QR and derive the shared AES key.
     * Sets [pairingResult] for the fragment to observe. Does NOT mutate the
     * local QR bitmap — the user may want to display the result and wait.
     */
    fun onPeerScanned(peerJson: String) {
        viewModelScope.launch {
            _pairingResult.value = pairWithPeer(peerJson)
        }
    }

    private fun pairWithPeer(peerJson: String): PairingResult {
        return try {
            val obj = JSONObject(peerJson)
            val peerPubKeyB64 = obj.optString("pubkey")
            val peerEndpoint = obj.optString("endpoint")
            val ts = obj.optLong("ts", 0L)
            if (peerPubKeyB64.isEmpty() || peerEndpoint.isEmpty()) return PairingResult.Invalid
            if (ts > 0 && System.currentTimeMillis() - ts > QR_EXPIRY_MS) return PairingResult.Expired
            val ourPriv = ourKeyPair?.private ?: return PairingResult.MissingLocalKey
            val peerKeyBytes = Base64.getDecoder().decode(peerPubKeyB64)
            val peerPublicKey = decodeEC256PublicKey(peerKeyBytes)
            val sharedKey: SecretKey = CryptoUtils.deriveSharedAESKey(ourPriv, peerPublicKey)
            Timber.i("QR handshake — shared AES-256 derived, endpoint=$peerEndpoint, key=${sharedKey.encoded.size}B")
            // TODO: hand sharedKey to relay client and POST encrypted profile.
            PairingResult.Success(peerEndpoint)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse QR payload")
            PairingResult.Error(e.message ?: "unknown")
        }
    }

    fun consumePairingResult() {
        _pairingResult.value = null
    }

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
