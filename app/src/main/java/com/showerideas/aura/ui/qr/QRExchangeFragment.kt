package com.showerideas.aura.ui.qr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.showerideas.aura.ui.contacts.ContactMergeBottomSheet
import com.showerideas.aura.utils.IdenticonGenerator
import dagger.hilt.android.AndroidEntryPoint
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Fallback exchange path for environments where BLE / Wi-Fi P2P is blocked
 * (e.g. enterprise meeting rooms). Two phones can bootstrap an ECDH session
 * by visually swapping QR codes:
 *
 *  1. Each side generates an ephemeral ECDH keypair (owned by
 *     [QRExchangeViewModel] so it survives rotation).
 *  2. The local public key + a random endpoint UUID + a timestamp are
 *     encoded as JSON and rendered as a QR code.
 *  3. "Scan their QR" opens a ZXing scanner; the scanned JSON contains the
 *     peer's public key, from which a shared AES key is derived locally.
 *  4. Encrypted profiles are swapped via the configured HTTPS relay.
 *  5. After decrypting the peer's profile a SAS (Short Authentication String)
 *     dialog is shown. Both parties verbally confirm the 6-digit code matches
 *     before the contact is saved -- protecting against relay-based MITM attacks.
 *
 * The QR payload self-expires after 60 s, refreshed by the ViewModel.
 */
@AndroidEntryPoint
class QRExchangeFragment : Fragment() {

    private var _binding: FragmentQrExchangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: QRExchangeViewModel by viewModels()

    /**
     * Guard against showing the SAS dialog twice if the fragment is recreated
     * (config change) while [QRExchangeViewModel.PairingResult.AwaitingSasConfirmation]
     * is still the active state. Reset on each new scan.
     */
    private var sasDialogShown = false
    /** Auto-aborts the SAS dialog after 30 s of inaction. */
    private var sasTimeoutJob: Job? = null

    companion object {
        private const val SAS_DIALOG_TIMEOUT_MS = 30_000L
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.qr_scan_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        sasDialogShown = false   // fresh scan resets the dialog guard
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
                        if (bitmap != null) binding.ivQrCode.setImageBitmap(bitmap)
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
                        handlePairingResult(result)
                    }
                }
            }
        }
    }

    private fun handlePairingResult(result: QRExchangeViewModel.PairingResult?) {
        if (result == null) return
        when (result) {
            is QRExchangeViewModel.PairingResult.AwaitingSasConfirmation -> {
                // Do NOT call consumePairingResult -- hold this state until the user
                // dismisses the dialog (confirm saves; mismatch discards + navigates away).
                if (!sasDialogShown) {
                    sasDialogShown = true
                    showSasDialog(result.sasPin)
                }
            }
            is QRExchangeViewModel.PairingResult.Success -> {
                sasDialogShown = false
                val name = result.contact.displayName
                    .ifBlank { result.contact.sourceEndpointId.take(8) }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.qr_pairing_ok, name),
                    Toast.LENGTH_LONG
                ).show()
                viewModel.consumePairingResult()
                // Show merge review sheet if a returning contact updated their card.
                val mergeEvent = result.mergeEvent
                if (mergeEvent != null && mergeEvent.hasChanges) {
                    ContactMergeBottomSheet.newInstance(mergeEvent) { selections ->
                        viewModel.applyMergeSelections(mergeEvent.preserved, selections)
                    }.show(childFragmentManager, ContactMergeBottomSheet.TAG)
                }
                findNavController().navigateUp()
            }
            is QRExchangeViewModel.PairingResult.RelayTimeout -> {
                sasDialogShown = false
                Toast.makeText(requireContext(), R.string.qr_relay_timeout, Toast.LENGTH_LONG).show()
                viewModel.consumePairingResult()
            }
            is QRExchangeViewModel.PairingResult.Expired -> {
                sasDialogShown = false
                Toast.makeText(requireContext(), R.string.qr_expired, Toast.LENGTH_LONG).show()
                viewModel.consumePairingResult()
            }
            is QRExchangeViewModel.PairingResult.Invalid,
            is QRExchangeViewModel.PairingResult.MissingLocalKey -> {
                sasDialogShown = false
                Toast.makeText(requireContext(), R.string.qr_invalid, Toast.LENGTH_LONG).show()
                viewModel.consumePairingResult()
            }
            is QRExchangeViewModel.PairingResult.Error -> {
                sasDialogShown = false
                val isMitmAbort = result.message.contains("MITM", ignoreCase = true) ||
                    result.message.contains("mismatch", ignoreCase = true)
                val msgRes = if (isMitmAbort) R.string.qr_sas_mismatch_abort else R.string.qr_invalid
                Toast.makeText(requireContext(), msgRes, Toast.LENGTH_LONG).show()
                viewModel.consumePairingResult()
            }
        }
    }

    /**
     * Display the SAS (Short Authentication String) verification dialog.
     *
     * Both parties see the same 6-digit code derived from their ephemeral ECDH
     * public keys. A verbal comparison catches a MITM who substituted a key at
     * the relay layer. Confirming saves the contact to Room; reporting a mismatch
     * discards the pending contact without persisting anything.
     */
    /**
     * Display the SAS verification dialog with a 30-second auto-abort countdown.
     *
     * Haptic feedback is fired immediately to draw attention. If neither button
     * is pressed within 30 s the session is aborted — the pending contact is
     * discarded without being saved.
     */
    /**
     * Custom SAS dialog with identicon + accessible code display.
     * Both parties derive the same identicon from the SAS pin — provides a secondary
     * visual verification channel alongside the 6-digit code.
     */
    private fun showSasDialog(pin: String) {
        // Haptic pulse — draws attention in noisy environments.
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // Inflate custom view with identicon + accessible code display.
        val dialogView = layoutInflater.inflate(R.layout.dialog_sas_verification, null)
        val identicon = IdenticonGenerator.generate(pin, size = 256)
        dialogView.findViewById<ImageView>(R.id.iv_sas_identicon).setImageBitmap(identicon)
        val tvCode = dialogView.findViewById<TextView>(R.id.tv_sas_code)
        tvCode.text = pin
        tvCode.contentDescription = getString(R.string.sas_code_desc) + " " +
            pin.toCharArray().joinToString(" ")
        dialogView.findViewById<TextView>(R.id.tv_sas_instruction)
            .text = getString(R.string.sas_dialog_instruction)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.sas_dialog_title))
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.sas_dialog_confirm)) { _, _ ->
                sasTimeoutJob?.cancel()
                viewModel.confirmSas()
            }
            .setNegativeButton(getString(R.string.sas_dialog_mismatch)) { _, _ ->
                sasTimeoutJob?.cancel()
                viewModel.abortSas()
                viewModel.consumePairingResult()
            }
            .show()

        // 30-second auto-abort — prevents indefinite VERIFYING state.
        sasTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SAS_DIALOG_TIMEOUT_MS)
            if (dialog.isShowing) {
                Timber.w("QR SAS dialog timed out — auto-aborting")
                dialog.dismiss()
                viewModel.abortSas()
                viewModel.consumePairingResult()
            }
        }
    }

    override fun onDestroyView() {
        sasTimeoutJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
