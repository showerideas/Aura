package com.showerideas.aura.ui.exchange

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
import com.showerideas.aura.R
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.databinding.FragmentExchangeBinding
import com.showerideas.aura.model.ExchangeSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The active exchange screen.
 *
 * UX flow:
 * 1. User arrives here after triple-press or tapping the Activate button
 * 2. If a gesture pattern is set, they must perform it to confirm intent
 * 3. Once gesture validated (or skipped if none set), exchange begins
 * 4. Shows live status: ADVERTISING → DISCOVERING → CONNECTING → EXCHANGING → DONE
 * 5. On success, navigates to the new contact detail
 */
@AndroidEntryPoint
class ExchangeFragment : Fragment() {

    private var _binding: FragmentExchangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExchangeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show gesture confirmation section only if pattern is set
        if (viewModel.hasGesturePattern()) {
            binding.gestureConfirmSection.visibility = View.VISIBLE
            binding.tvGestureHint.text = "Hold and perform your gesture to confirm"
        } else {
            binding.gestureConfirmSection.visibility = View.GONE
        }

        // Gesture hold-to-confirm
        binding.btnConfirmGesture.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> viewModel.startGestureRecording()
                android.view.MotionEvent.ACTION_UP -> {
                    val matched = viewModel.stopGestureAndValidate()
                    if (matched) {
                        binding.tvGestureStatus.text = "Gesture confirmed!"
                        binding.gestureConfirmSection.visibility = View.GONE
                    } else {
                        binding.tvGestureStatus.text = "Gesture didn't match. Try again."
                    }
                }
            }
            true
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

    private fun updateUI(session: ExchangeSession) {
        val (statusText, showProgress) = when (session.state) {
            ExchangeSession.State.ADVERTISING -> "Broadcasting presence..." to true
            ExchangeSession.State.DISCOVERING -> "Looking for nearby AURA users..." to true
            ExchangeSession.State.CONNECTING -> "Found someone! Connecting..." to true
            ExchangeSession.State.EXCHANGING -> "Exchanging contact info securely..." to true
            ExchangeSession.State.COMPLETED -> {
                val name = session.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                    ?: "someone"
                "Exchanged with $name!" to false
            }
            ExchangeSession.State.CANCELLED -> "Exchange cancelled" to false
            ExchangeSession.State.ERROR -> "Something went wrong. Try again." to false
        }

        binding.tvStatus.text = statusText
        binding.progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE

        if (session.state == ExchangeSession.State.COMPLETED) {
            binding.btnCancel.text = "Done"
            binding.btnCancel.setOnClickListener {
                findNavController().navigate(R.id.action_exchange_to_contacts)
            }
        }

        if (session.state == ExchangeSession.State.ERROR) {
            Toast.makeText(requireContext(), "Exchange failed. Make sure Bluetooth and location are on.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
