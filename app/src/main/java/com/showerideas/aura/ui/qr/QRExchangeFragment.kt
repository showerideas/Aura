package com.showerideas.aura.ui.qr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentQrExchangeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Fallback exchange path for environments where BLE / Wi-Fi P2P is blocked
 * (e.g. enterprise meeting rooms). Two phones can still bootstrap an ECDH
 * session by visually swapping QR codes:
 *
 *  1. Each side generates an ephemeral ECDH keypair (owned by
 *     [QRExchangeViewModel] so it survives rotation — see FIX-D).
 *  2. The local public key + a random endpoint UUID + a timestamp are
 *     encoded as JSON and rendered as a QR code.
 *  3. The "Scan their QR" button opens a ZXing scanner; the scanned JSON
 *     contains the peer's public key, from which a shared AES key is
 *     derived locally.
 *  4. The shared key would then be used with a cloud relay to swap
 *     encrypted profiles — that relay endpoint is intentionally stubbed
 *     here. The crypto path is real.
 *
 * The QR payload self-expires after 60s, refreshed by the ViewModel.
 */
@AndroidEntryPoint
class QRExchangeFragment : Fragment() {

    private var _binding: FragmentQrExchangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: QRExchangeViewModel by viewModels()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.qr_scan_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        viewModel.onPeerScanned(contents)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQrExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.qrBitmap.collect { bitmap ->
                        if (bitmap != null) {
                            binding.ivQrCode.setImageBitmap(bitmap)
                        }
                    }
                }
                launch {
                    viewModel.secondsRemaining.collect { secs ->
                        if (secs <= 0) {
                            binding.tvQrCountdown.setText(R.string.qr_refreshing)
                        } else {
                            binding.tvQrCountdown.text =
                                getString(R.string.qr_countdown_template, secs)
                        }
                    }
                }
                launch {
                    viewModel.pairingResult.collect { result ->
                        if (result == null) return@collect
                        when (result) {
                            is QRExchangeViewModel.PairingResult.Success ->
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.qr_pairing_ok, result.peerEndpoint.take(8)),
                                    Toast.LENGTH_LONG
                                ).show()
                            is QRExchangeViewModel.PairingResult.Expired ->
                                Toast.makeText(requireContext(), R.string.qr_expired, Toast.LENGTH_LONG).show()
                            is QRExchangeViewModel.PairingResult.Invalid,
                            is QRExchangeViewModel.PairingResult.MissingLocalKey,
                            is QRExchangeViewModel.PairingResult.Error ->
                                Toast.makeText(requireContext(), R.string.qr_invalid, Toast.LENGTH_LONG).show()
                        }
                        viewModel.consumePairingResult()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
