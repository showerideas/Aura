package com.showerideas.aura.ui.exchange

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import timber.log.Timber
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.showerideas.aura.R
import com.showerideas.aura.auth.BiometricAuthHelper
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.auth.LivenessGuard
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.databinding.FragmentExchangeBinding
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.model.MergeEvent
import com.showerideas.aura.service.NearbyExchangeService
import com.showerideas.aura.ui.contacts.ContactMergeBottomSheet
import com.showerideas.aura.ui.exchange.SharePresetBottomSheet
import com.showerideas.aura.utils.IdenticonGenerator
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Active exchange screen.
 *
 * Camera gesture gate flow:
 * 1. Fragment starts CameraX + MediaPipe pipeline via [ExchangeViewModel.startGestureCamera].
 * 2. The user holds their saved hand gesture up to the front camera.
 * 3. When [GestureAuthManager.RecordingState.Complete] is emitted the embedding
 *    is automatically compared to the stored pattern (cosine similarity).
 * 4. Match  -> service gate opens, exchange starts, camera stops.
 *    No match -> camera is reset for a fresh attempt; 3 failures cancel.
 */
@AndroidEntryPoint
class ExchangeFragment : Fragment() {

    companion object {
        private const val MAX_GESTURE_ATTEMPTS = 3
        private const val SAS_DIALOG_TIMEOUT_MS = 30_000L
    }

    private var _binding: FragmentExchangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExchangeViewModel by viewModels()

    private var failedAttempts = 0
    private var serviceStarted = false
    /** Coroutine job that auto-aborts the SAS dialog after 30 s of inaction. */
    private var sasTimeoutJob: Job? = null
    /** Coroutine job that updates the progress-bar countdown tick every 100 ms. */
    private var sasCountdownJob: Job? = null
    /** Guard: show the merge review sheet at most once per completed session. */
    private var mergeSheetShown = false
    /**
     * Guard against the StateFlow replaying Complete after lifecycle transitions
     * (e.g. user briefly backgrounds the app). Once we've handled the result —
     * success or final failure — we no longer want to act on another emission.
     */
    private var gestureValidated = false
    /** Guard: show the "card updated" Snackbar at most once per completed session. */
    private var cardUpdatedSnackbarShown = false
    // sasDialogShown has been moved to ExchangeViewModel to survive configuration
    // changes (rotation, theme switch). The fragment-level variable was reset to
    // false on every recreation, causing the SAS dialog to appear twice.

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // show share-preset picker before auth gate if presets exist.
        // The picker is non-blocking: dismissal or selection both proceed to the auth gate.
        showSharePresetPickerThenAuth()

        // NFC bootstrap indicator — show the chip if MainActivity set a pending
        // NFC bootstrap before navigating here. The bootstrap is consumed by
        // NearbyExchangeService.startSession() so we snapshot it before the
        // service has a chance to clear it.
        val nfcBootstrapActive = NearbyExchangeService.pendingNfcBootstrap != null
        binding.chipNfcActive.visibility =
            if (nfcBootstrapActive) View.VISIBLE else View.GONE

        binding.btnCancel.setOnClickListener {
            viewModel.cancelExchange()
            findNavController().navigateUp()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionState.collect { session ->
                    session ?: return@collect
                    updateUI(session)
                }
            }
        }
    }

    // Gesture gate — camera-based hand embedding

    private fun startGestureGate() {
        if (viewModel.hasGesturePattern()) {
            binding.gestureConfirmSection.visibility = View.VISIBLE
            binding.tvGestureHint.setText(R.string.gesture_show_to_auth)
            // Start camera into the PreviewView declared in the layout
            viewModel.startGestureCamera(viewLifecycleOwner, binding.gesturePreview)
            observeGestureState()
        } else {
            binding.gestureConfirmSection.visibility = View.GONE
            showUnprotectedExchangeDialog()
        }
    }

    private fun observeGestureState() {
        // Show live gesture label and drive the stability progress bar
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveGestureState.collect { state ->
                    binding.tvGestureStatus.text = when (state) {
                        is CameraHandEmbedder.GestureState.NoHand -> {
                            binding.pbGestureStability.progress = 0
                            getString(R.string.gesture_no_hand)
                        }
                        is CameraHandEmbedder.GestureState.Detecting -> {
                            binding.pbGestureStability.progress = (state.stability * 100).toInt()
                            getString(
                                R.string.gesture_detecting,
                                state.gesture.emoji,
                                state.gesture.displayName,
                                (state.stability * 100).toInt()
                            )
                        }
                        is CameraHandEmbedder.GestureState.Stable -> {
                            binding.pbGestureStability.progress = 100
                            getString(R.string.gesture_stable,
                                state.gesture.emoji, state.gesture.displayName)
                        }
                        is CameraHandEmbedder.GestureState.ModelError -> {
                            binding.pbGestureStability.progress = 0
                            getString(R.string.gesture_model_error)
                        }
                    }
                }
            }
        }

        // Auto-validate when a stable embedding is ready.
        // The gestureValidated flag prevents double-firing if the StateFlow
        // replays Complete after a lifecycle STOPPED→STARTED transition.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestureRecordingState.collect { state ->
                    when {
                        state is GestureAuthManager.RecordingState.Complete && !gestureValidated ->
                            onGestureComplete()
                        state is GestureAuthManager.RecordingState.Error ->
                            binding.tvGestureStatus.text = state.message
                        else -> Unit
                    }
                }
            }
        }

        // Real-time liveness indicator — the gesture hint text gains a small
        // emoji badge so the user can see the anti-spoofing guard is active.
        // We update tvGestureHint (the static "hold your gesture" label)
        // rather than tvGestureStatus to avoid clobbering dynamic detection text.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.livenessResult.collect { result ->
                    val badge = when (result) {
                        is LivenessGuard.Result.Collecting -> getString(R.string.gesture_show_to_auth)
                        is LivenessGuard.Result.Live       ->
                            getString(R.string.gesture_show_to_auth) + "  \uD83D\uDFE2"   // 🟢
                        is LivenessGuard.Result.Spoof      ->
                            getString(R.string.gesture_show_to_auth) + "  \u26A0\uFE0F"   // ⚠️
                    }
                    binding.tvGestureHint.text = badge
                }
            }
        }
    }

    private fun onGestureComplete() {
        // Mark as handled immediately so lifecycle re-subscription can't
        // trigger a second call before the state transitions away from Complete.
        gestureValidated = true

        val matched = viewModel.validateGestureAndUnlockService()
        if (matched) {
            failedAttempts = 0
            binding.pbGestureStability.progress = 100
            binding.tvGestureStatus.setText(R.string.exchange_gesture_confirmed)
            binding.gestureConfirmSection.visibility = View.GONE
            viewModel.stopGestureCamera()
            startServiceOnce()
        } else {
            // Allow the next attempt to be validated.
            gestureValidated = false
            failedAttempts++
            val remaining = (MAX_GESTURE_ATTEMPTS - failedAttempts).coerceAtLeast(0)
            if (failedAttempts >= MAX_GESTURE_ATTEMPTS) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.exchange_gesture_failed_max),
                    Toast.LENGTH_LONG
                ).show()
                viewModel.cancelExchange()
                findNavController().navigateUp()
            } else {
                binding.pbGestureStability.progress = 0
                binding.tvGestureStatus.text =
                    getString(R.string.exchange_gesture_failed_retry, remaining)
                // Reset so the user must hold the gesture again
                viewModel.resetGestureCapture()
            }
        }
    }

    // Biometric gate (unchanged)

    private fun startBiometricGate() {
        binding.gestureConfirmSection.visibility = View.GONE
        if (!BiometricAuthHelper.isAvailable(requireContext())) {
            Toast.makeText(
                requireContext(),
                getString(R.string.biometric_unavailable_fallback),
                Toast.LENGTH_SHORT
            ).show()
            startGestureGate()
            return
        }
        BiometricAuthHelper.authenticate(
            fragment  = this,
            onSuccess = { viewModel.markExchangeVerified(); startServiceOnce() },
            onFailure = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.cancelExchange()
                findNavController().navigateUp()
            }
        )
    }

    // Helpers

    private fun showUnprotectedExchangeDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.exchange_unprotected_title)
            .setMessage(R.string.exchange_unprotected_message)
            .setCancelable(false)
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                viewModel.cancelExchange(); findNavController().navigateUp()
            }
            .setPositiveButton(R.string.action_continue) { _, _ ->
                viewModel.proceedWithoutGesture(); startServiceOnce()
            }
            .show()
    }


    // Share preset picker

    /**
     * Show [SharePresetBottomSheet] if the user has any presets defined.
     * After the user picks a preset (or dismisses the sheet), proceed to the
     * auth gate so exchange setup isn't blocked on the picker.
     */
    private fun showSharePresetPickerThenAuth() {
        val presets = viewModel.sharePresets.value
        if (presets.isNotEmpty()) {
            val sheet = SharePresetBottomSheet(onPresetSelected = { _ ->
                // Preset was applied via ExchangeViewModel.selectPreset() inside the sheet.
                // Proceed to the auth gate.
                startAuthGate()
            })
            sheet.show(childFragmentManager, SharePresetBottomSheet.TAG)
            // Also start the auth gate immediately so the user isn't forced to interact
            // with the sheet — they can dismiss it and the exchange is already proceeding.
            sheet.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    // Sheet dismissed without selecting — start auth gate if not already started.
                    if (!serviceStarted && !gestureValidated) startAuthGate()
                }
            })
        } else {
            startAuthGate()
        }
    }

    /** Kick off the appropriate auth gate (gesture or biometric) based on the current setting. */
    private fun startAuthGate() {
        when (viewModel.authMethod.value) {
            AuthPreferences.METHOD_BIOMETRIC -> startBiometricGate()
            else                             -> startGestureGate()
        }
    }

    private fun startServiceOnce() {
        if (serviceStarted) return
        serviceStarted = true
        NearbyExchangeService.start(requireContext())
    }

    private fun updateUI(session: ExchangeSession) {
        val (statusText, showProgress) = when (session.state) {
            ExchangeSession.State.ADVERTISING -> getString(R.string.status_advertising) to true
            ExchangeSession.State.DISCOVERING -> getString(R.string.status_discovering) to true
            ExchangeSession.State.CONNECTING  -> getString(R.string.status_connecting)  to true
            ExchangeSession.State.VERIFYING   -> getString(R.string.status_verifying)   to false
            ExchangeSession.State.EXCHANGING  -> getString(R.string.status_exchanging)  to true
            ExchangeSession.State.COMPLETED   -> {
                val name = session.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.someone)
                getString(R.string.exchange_completed, name) to false
            }
            ExchangeSession.State.CANCELLED -> getString(R.string.exchange_cancelled) to false
            ExchangeSession.State.ERROR     -> getString(R.string.exchange_error_generic) to false
        }
        binding.tvStatus.text = statusText
        binding.progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE

        // Show SAS verification dialog exactly once per VERIFYING state.
        // sasDialogShown is ViewModel-backed (survives rotation); onSasDialogShown()
        // marks it so the dialog is not shown again if the fragment is recreated
        // while the service is still in VERIFYING.
        if (session.state == ExchangeSession.State.VERIFYING &&
            session.sasPin != null &&
            !viewModel.sasDialogShown.value
        ) {
            viewModel.onSasDialogShown()
            showSasDialog(session.sasPin)
        }

        if (session.state == ExchangeSession.State.COMPLETED) {
            binding.btnCancel.setText(R.string.action_done)
            binding.btnCancel.setOnClickListener {
                findNavController().navigate(R.id.action_exchange_to_contacts)
            }
            // Show merge review sheet when a returning contact updated their card.
            val mergeEvent: MergeEvent? = session.mergeEvent
            if (mergeEvent != null && mergeEvent.hasChanges && !mergeSheetShown) {
                mergeSheetShown = true
                ContactMergeBottomSheet.newInstance(mergeEvent) { selections ->
                    viewModel.applyMergeSelections(mergeEvent.preserved, selections)
                }.show(childFragmentManager, ContactMergeBottomSheet.TAG)
            }
            // show "Card updated" banner if this peer bumped their profile version.
            if (session.profileVersionBumped && !cardUpdatedSnackbarShown) {
                cardUpdatedSnackbarShown = true
                val name = session.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.someone)
                Snackbar.make(
                    binding.root,
                    getString(R.string.contact_card_updated, name),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
        if (session.state == ExchangeSession.State.ERROR) {
            val error = session.errorMessage ?: getString(R.string.exchange_error_generic)
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Display the SAS (Short Authentication String) verification dialog.
     *
     * Both parties see the same 6-digit PIN derived from their ephemeral ECDH keys.
     * If a MITM substituted their own key, the PINs will differ.
     *
     * Security: this is an out-of-band verbal comparison. The user must confirm
     * the codes match WITH THEIR PEER IN PERSON before pressing "Match ✓".
     * Pressing "Mismatch" aborts the session with an error — no profile data is
     * transmitted to either party.
     */
    /**
     * Display the SAS verification dialog with a 30-second auto-abort countdown.
     *
     * Haptic feedback is fired immediately to draw the user's attention even in
     * noisy environments. If neither button is pressed within 30 s the dialog is
     * dismissed and the session is aborted as a precaution.
     */
    /**
     * Display the SAS verification dialog with:
     * - An identicon generated from the SAS pin (both parties see the same identicon)
     * - A large, accessible, monospace code display (accessibilityLiveRegion=polite)
     * - A 30-second auto-abort countdown
     * - Haptic feedback on appearance
     *
     * Both visual channels (6-digit code AND identicon) must match for the exchange
     * to be considered verified — provides defence-in-depth against MITM.
     */
    private fun showSasDialog(pin: String) {
        // Haptic pulse to draw attention
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // Inflate custom view with identicon + accessible code display + countdown bar.
        val dialogView = layoutInflater.inflate(R.layout.dialog_sas_verification, null)
        val identicon = IdenticonGenerator.generate(pin, size = 256)
        dialogView.findViewById<ImageView>(R.id.iv_sas_identicon).setImageBitmap(identicon)
        val tvCode = dialogView.findViewById<TextView>(R.id.tv_sas_code)
        tvCode.text = pin
        // TalkBack reads "Security code 1 2 3 4 5 6" so each digit is pronounced separately.
        tvCode.contentDescription = getString(R.string.sas_code_desc) + " " +
            pin.toCharArray().joinToString(" ")
        dialogView.findViewById<TextView>(R.id.tv_sas_instruction)
            .text = getString(R.string.sas_dialog_instruction)

        // countdown progress bar (max=300 steps, 1 step per 100 ms = 30 s total)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.pb_sas_countdown)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.sas_dialog_title))
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.sas_dialog_confirm)) { _, _ ->
                sasTimeoutJob?.cancel()
                sasCountdownJob?.cancel()
                viewModel.confirmSas()
            }
            .setNegativeButton(getString(R.string.sas_dialog_mismatch)) { _, _ ->
                sasTimeoutJob?.cancel()
                sasCountdownJob?.cancel()
                viewModel.abortSas()
                findNavController().navigateUp()
            }
            .show()

        // Tick the progress bar every 100 ms
        sasCountdownJob = viewLifecycleOwner.lifecycleScope.launch {
            var remaining = 300
            while (remaining > 0 && dialog.isShowing) {
                delay(100)
                remaining--
                progressBar.progress = remaining
            }
        }

        // 30-second auto-abort — if user walks away without confirming,
        // abort the session rather than leaving it in a limbo VERIFYING state.
        sasTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SAS_DIALOG_TIMEOUT_MS)
            if (dialog.isShowing) {
                Timber.w("SAS dialog timed out after ${SAS_DIALOG_TIMEOUT_MS / 1000}s — auto-aborting")
                sasCountdownJob?.cancel()
                dialog.dismiss()
                viewModel.abortSas()
                findNavController().navigateUp()
            }
        }
    }

    override fun onDestroyView() {
        sasTimeoutJob?.cancel()
        sasCountdownJob?.cancel()
        viewModel.stopGestureCamera()
        super.onDestroyView()
        _binding = null
    }
}
