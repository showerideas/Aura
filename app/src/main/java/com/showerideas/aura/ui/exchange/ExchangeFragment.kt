package com.showerideas.aura.ui.exchange

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.databinding.FragmentExchangeBinding
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.AndroidEntryPoint
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
    }

    private var _binding: FragmentExchangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExchangeViewModel by viewModels()

    private var failedAttempts = 0
    private var serviceStarted = false
    /**
     * Guard against the StateFlow replaying Complete after lifecycle transitions
     * (e.g. user briefly backgrounds the app). Once we've handled the result —
     * success or final failure — we no longer want to act on another emission.
     */
    private var gestureValidated = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        when (viewModel.authMethod.value) {
            AuthPreferences.METHOD_BIOMETRIC -> startBiometricGate()
            else                             -> startGestureGate()
        }

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

    // -------------------------------------------------------------------------
    // Gesture gate — camera-based hand embedding
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Biometric gate (unchanged)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

        if (session.state == ExchangeSession.State.COMPLETED) {
            binding.btnCancel.setText(R.string.action_done)
            binding.btnCancel.setOnClickListener {
                findNavController().navigate(R.id.action_exchange_to_contacts)
            }
        }
        if (session.state == ExchangeSession.State.ERROR) {
            val error = session.errorMessage ?: getString(R.string.exchange_error_generic)
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        viewModel.stopGestureCamera()
        super.onDestroyView()
        _binding = null
    }
}
