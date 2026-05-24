package com.showerideas.aura.ui.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentHomeBinding
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    /** PR-18: reference held so we can cancel cleanly in onDestroyView. */
    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnActivate.setOnClickListener {
            // The exchange fragment is responsible for running the gesture
            // gate and then starting NearbyExchangeService once the gate is
            // opened. We must not start the service before that — see PR-01.
            findNavController().navigate(R.id.action_home_to_exchange)
        }

        // PR-08: QR fallback for environments where BLE / Wi-Fi P2P is blocked.
        binding.btnQrFallback.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_qr)
        }

        // PR-09: room mode — also reachable via long-press on the AURA button.
        binding.btnRoomMode.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_room)
        }
        binding.btnActivate.setOnLongClickListener {
            findNavController().navigate(R.id.action_home_to_room)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profile.collect { profile ->
                    binding.tvGreeting.text = if (profile?.displayName?.isNotBlank() == true) {
                        getString(R.string.home_greeting, profile.displayName)
                    } else {
                        getString(R.string.home_greeting_no_profile)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentContacts.collect { contacts ->
                    binding.tvContactCount.text =
                        getString(R.string.home_contact_count, contacts.size)
                }
            }
        }

        // PR-18: start the idle-state pulse the moment the screen is up.
        startPulse(R.color.aura_purple)

        // PR-18: while an exchange session is live, switch the pulse to cyan
        // so the home screen reflects the service state if the user backs
        // out of the Exchange screen.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NearbyExchangeService.sessionState.collect { session ->
                    // FIX-4: ROOM_HOST/ROOM_GUEST removed from State enum; topology
                    // is now expressed via session.mode. Active states are the four
                    // in-progress stage values regardless of mode.
                    val active = session != null && session.state in setOf(
                        ExchangeSession.State.ADVERTISING,
                        ExchangeSession.State.DISCOVERING,
                        ExchangeSession.State.CONNECTING,
                        ExchangeSession.State.VERIFYING,
                        ExchangeSession.State.EXCHANGING
                    )
                    startPulse(if (active) R.color.aura_cyan else R.color.aura_purple)
                }
            }
        }
    }

    /**
     * PR-18: replace the current pulse animation with a new one tinted to
     * [colorRes]. Idempotent on the same colour — cancelling and restarting
     * is cheap enough that we don't bother diffing the current state.
     */
    private fun startPulse(colorRes: Int) {
        val ctx = context ?: return
        val binding = _binding ?: return
        pulseAnimator?.cancel()
        binding.pulseRing.setBackgroundColor(ContextCompat.getColor(ctx, colorRes))
        // Material's circle_pulse_bg drawable is an oval — setBackgroundColor
        // would clobber the shape, so we re-attach the shape drawable and
        // then tint it instead.
        binding.pulseRing.background = ContextCompat.getDrawable(ctx, R.drawable.circle_pulse_bg)
            ?.mutate()?.apply {
                setTint(ContextCompat.getColor(ctx, colorRes))
            }
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.pulseRing,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0f)
        ).apply {
            duration = 1400
            repeatMode = ObjectAnimator.RESTART
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    override fun onDestroyView() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroyView()
        _binding = null
    }
}
