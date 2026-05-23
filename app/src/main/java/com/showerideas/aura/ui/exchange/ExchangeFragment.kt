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
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.databinding.FragmentExchangeBinding
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The active exchange screen.
 *
 * UX flow:
 * 1. User arrives here after triple-press or tapping the Activate button
 * 2. Gate enforcement:
 *    - If a gesture pattern is set, the user MUST perform the gesture.
 *      btnConfirmGesture is the only path to advancing the exchange.
 *      Three failed attempts cancel the session.
 *    - If no pattern is set, an AlertDialog asks the user to confirm an
 *      unprotected exchange. Cancel → navigate back. Continue → unlock
 *      the service gate directly.
 * 3. Once the gate is opened, the [NearbyExchangeService] is started and
 *    shows live status: ADVERTISING → DISCOVERING → CONNECTING → EXCHANGING → DONE
 * 4. On success, navigates to the new contact detail.
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // PR-16: branch on the user's selected auth method. Biometric is
        // routed through the AndroidX BiometricPrompt and bypasses the
        // gesture flow entirely. Gesture remains the default for any user
        // who hasn't visited Settings to change it (PR-19).
        when (viewModel.authMethod.value) {
            AuthPreferences.METHOD_BIOMETRIC -> startBiometricGate()
            else -> startGestureGate()
        }

        binding.btnCancel.setOnClickListener {
            viewModel.cancelExchange()
            findNavController().navigateUp()
        }

        // Observe session state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionState.collect { session ->
                    session ?: return@collect
                    updateUI(session)
                }
            }
        }
    }

    /**
     * PR-16: original gesture/no-gesture branch, extracted so the new
     * biometric path can call it as a fallback when the device reports
     * no usable biometric hardware.
     */
    private fun startGestureGate() {
        if (viewModel.hasGesturePattern()) {
            binding.gestureConfirmSection.visibility = View.VISIBLE
            binding.tvGestureHint.text = getString(R.string.exchange_gesture_hint)
            wireGestureButton()
        } else {
            binding.gestureConfirmSection.visibility = View.GONE
            showUnprotectedExchangeDialog()
        }
    }

    /**
     * PR-16: biometric gate. Hides the gesture confirm UI and shows the
     * system BiometricPrompt. On success the same service-gate flip
     * happens as for a confirmed gesture; on failure we navigate back.
     * If the device has no biometric hardware enrolled we silently
     * fall back to the gesture flow.
     */
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
            fragment = this,
            onSuccess = {
                viewModel.markExchangeVerified()
                startServiceOnce()
            },
            onFailure = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.cancelExchange()
                findNavController().navigateUp()
            }
        )
    }

    private fun wireGestureButton() {
        binding.btnConfirmGesture.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> viewModel.startGestureRecording()
                android.view.MotionEvent.ACTION_UP -> onGestureReleased()
            }
            true
        }
    }

    private fun onGestureReleased() {
        val matched = viewModel.validateGestureAndUnlockService()
        if (matched) {
            failedAttempts = 0
            binding.tvGestureStatus.text = getString(R.string.exchange_gesture_confirmed)
            binding.gestureConfirmSection.visibility = View.GONE
            startServiceOnce()
        } else {
            failedAttempts += 1
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
                binding.tvGestureStatus.text =
                    getString(R.string.exchange_gesture_failed_retry, remaining)
                // Keep the gesture section visible for retry
                binding.gestureConfirmSection.visibility = View.VISIBLE
            }
        }
    }

    private fun showUnprotectedExchangeDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.exchange_unprotected_title)
            .setMessage(R.string.exchange_unprotected_message)
            .setCancelable(false)
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                viewModel.cancelExchange()
                findNavController().navigateUp()
            }
            .setPositiveButton(R.string.action_continue) { _, _ ->
                viewModel.proceedWithoutGesture()
                startServiceOnce()
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
            ExchangeSession.State.CONNECTING -> getString(R.string.status_connecting) to true
            ExchangeSession.State.EXCHANGING -> getString(R.string.status_exchanging) to true
            ExchangeSession.State.COMPLETED -> {
                val name = session.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.someone)
                getString(R.string.exchange_completed, name) to false
            }
            ExchangeSession.State.CANCELLED -> getString(R.string.exchange_cancelled) to false
            ExchangeSession.State.ERROR -> getString(R.string.exchange_error_generic) to false
            // FIX-4: ROOM_HOST / ROOM_GUEST removed from State enum. Room topology
            // is now expressed via session.mode (ExchangeMode.ROOM_HOST / ROOM_GUEST).
            // No dead branches needed — the when is now exhaustive over stage values only.
        }

        binding.tvStatus.text = statusText
        binding.progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE

        if (session.state == ExchangeSession.State.COMPLETED) {
            binding.btnCancel.text = getString(R.string.action_done)
            binding.btnCancel.setOnClickListener {
                findNavController().navigate(R.id.action_exchange_to_contacts)
            }
        }

        if (session.state == ExchangeSession.State.ERROR) {
            Toast.makeText(
                requireContext(),
                getString(R.string.exchange_error_bluetooth),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
