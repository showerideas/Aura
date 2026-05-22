package com.showerideas.aura.ui.qr

import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentQrExchangeBinding
import com.showerideas.aura.utils.CryptoUtils
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import timber.log.Timber
import java.util.Base64
import java.util.UUID

/**
 * Fallback exchange path for environments where BLE / Wi-Fi P2P is blocked
 * (e.g. enterprise meeting rooms). Two phones can still bootstrap an ECDH
 * session by visually swapping QR codes:
 *
 *  1. Each side generates an ephemeral ECDH keypair on [onViewCreated].
 *  2. The local public key + a random endpoint UUID + a timestamp are
 *     encoded as JSON and rendered as a QR code.
 *  3. The "Scan their QR" button opens a ZXing scanner; the scanned JSON
 *     contains the peer's public key, from which a shared AES key is
 *     derived locally.
 *  4. The shared key would then be used with a cloud relay to swap
 *     encrypted profiles — that relay endpoint is intentionally stubbed
 *     here. The crypto path is real.
 *
 * The QR payload self-expires after [QR_EXPIRY_MS] (60 s).
 */
@AndroidEntryPoint
class QRExchangeFragment : Fragment() {

    companion object {
        private const val QR_EXPIRY_MS = 60_000L
        private const val QR_PAYLOAD_VERSION = 1
    }

    private var _binding: FragmentQrExchangeBinding? = null
    private val binding get() = _binding!!

    private var ourKeyPair: java.security.KeyPair? = null
    private var localEndpoint: String = UUID.randomUUID().toString()
    private var countdownTimer: CountDownTimer? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.qr_scan_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        handleScannedPayload(contents)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQrExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        regenerateAndRender()

        binding.btnScanTheirQr.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.qr_scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            scanLauncher.launch(options)
        }
        binding.btnQrCancel.setOnClickListener { findNavController().navigateUp() }
    }

    private fun regenerateAndRender() {
        ourKeyPair = CryptoUtils.generateEphemeralECDHKeyPair()
        localEndpoint = UUID.randomUUID().toString()
        val kp = ourKeyPair ?: return

        val payload = JSONObject().apply {
            put("v", QR_PAYLOAD_VERSION)
            put("pubkey", Base64.getEncoder().encodeToString(kp.public.encoded))
            put("endpoint", localEndpoint)
            put("ts", System.currentTimeMillis())
        }.toString()

        try {
            val bitMatrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 800, 800)
            val bitmap: Bitmap = BarcodeEncoder().createBitmap(bitMatrix)
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: WriterException) {
            Timber.e(e, "Failed to render QR")
            Toast.makeText(requireContext(), R.string.qr_render_failed, Toast.LENGTH_LONG).show()
        }

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(QR_EXPIRY_MS, 1_000) {
            override fun onTick(remainingMs: Long) {
                val secs = (remainingMs / 1000).toInt().coerceAtLeast(0)
                binding.tvQrCountdown.text =
                    getString(R.string.qr_countdown_template, secs)
            }
            override fun onFinish() {
                binding.tvQrCountdown.setText(R.string.qr_refreshing)
                regenerateAndRender()
            }
        }.start()
    }

    private fun handleScannedPayload(json: String) {
        try {
            val obj = JSONObject(json)
            val peerPubKeyB64 = obj.optString("pubkey")
            val peerEndpoint = obj.optString("endpoint")
            val ts = obj.optLong("ts", 0L)
            if (peerPubKeyB64.isEmpty() || peerEndpoint.isEmpty()) {
                Toast.makeText(requireContext(), R.string.qr_invalid, Toast.LENGTH_LONG).show()
                return
            }
            if (ts > 0 && System.currentTimeMillis() - ts > QR_EXPIRY_MS) {
                Toast.makeText(requireContext(), R.string.qr_expired, Toast.LENGTH_LONG).show()
                return
            }

            val ourPriv = ourKeyPair?.private ?: return
            val peerKeyBytes = Base64.getDecoder().decode(peerPubKeyB64)
            val peerPublicKey = decodeEC256PublicKey(peerKeyBytes)
            val sharedKey = CryptoUtils.deriveSharedAESKey(ourPriv, peerPublicKey)
            Timber.i("QR handshake — shared AES-256 derived, endpoint=$peerEndpoint, key=${sharedKey.encoded.size}B")

            // TODO: Implement relay: POST encrypted profile to
            //  https://relay.aura.app/exchange/$peerEndpoint
            // For now we confirm the crypto path locally and surface a toast.
            Toast.makeText(
                requireContext(),
                getString(R.string.qr_pairing_ok, peerEndpoint.take(8)),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse QR payload")
            Toast.makeText(requireContext(), R.string.qr_invalid, Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeEC256PublicKey(encoded: ByteArray): java.security.PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        return java.security.KeyFactory.getInstance("EC").generatePublic(spec)
    }

    override fun onDestroyView() {
        countdownTimer?.cancel()
        countdownTimer = null
        super.onDestroyView()
        _binding = null
    }
}
