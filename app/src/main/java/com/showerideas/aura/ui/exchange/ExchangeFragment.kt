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

        if (viewModel.hasGesturePattern()) {
            // Gate via gesture confirmation
            binding.gestureConfirmSection.visibility = View.VISIBLE
            binding.tvGestureHint.text = getString(R.string.exchange_gesture_hint)
            wireGestureButton()
        } else {
            // No gesture set — confirm the user really wants to exchange
            // without authentication, then unlock the gate directly.
            binding.gestureConfirmSection.visibility = View.GONE
            showUnprotectedExchangeDialog()
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
