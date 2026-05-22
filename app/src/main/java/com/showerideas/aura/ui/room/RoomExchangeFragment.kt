package com.showerideas.aura.ui.room

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
import com.showerideas.aura.databinding.FragmentRoomExchangeBinding
import com.showerideas.aura.model.ExchangeSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Room exchange screen. The top toggle picks host vs guest:
 *   - HOST: keeps advertising and collects multiple guest cards until
 *     the user taps "Close room" or 5 minutes elapses. Live count of
 *     joiners is shown below the status label.
 *   - GUEST: connects to the first advertising host found and
 *     terminates after a single card exchange.
 *
 * Auth (PR-09 + PR-01): before either path actually starts the service
 * we must unlock the service-level gesture gate. We mirror
 * [com.showerideas.aura.ui.exchange.ExchangeFragment]:
 *   - If a pattern is stored, the user must perform it (one tap → record
 *     for ~2s on action button press) — up to 3 attempts. On the third
 *     failure we cancel and navigate up.
 *   - If no pattern is stored, an AlertDialog confirms an unprotected
 *     exchange. Continue → [RoomExchangeViewModel.proceedWithoutGesture].
 */
@AndroidEntryPoint
class RoomExchangeFragment : Fragment() {

    companion object {
        private const val MAX_GESTURE_ATTEMPTS = 3
    }

    private var _binding: FragmentRoomExchangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RoomExchangeViewModel by viewModels()

    private var isHostMode: Boolean = true
    private var sessionRunning: Boolean = false
    private var failedGestureAttempts = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoomExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.roomModeToggle.check(R.id.btn_mode_host)
        binding.roomModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isHostMode = (checkedId == R.id.btn_mode_host)
            renderInitialState()
        }
        renderInitialState()

        // Two paths through the action button (mirrors ExchangeFragment):
        //   1. If a session is already running OR no gesture is stored, a
        //      plain tap is the action (close-room / open-confirm dialog).
        //   2. If a gesture is stored, press-and-hold the button to record;
        //      releasing the button runs DTW + unlocks the service gate.
        // We use an onTouchListener so press/release are unambiguously
        // paired — the previous long-press + tap-again flow was fragile.
        binding.btnRoomAction.setOnTouchListener { v, event ->
            val useTouchGesture = !sessionRunning && viewModel.hasGesturePattern()
            if (!useTouchGesture) {
                if (event.action == android.view.MotionEvent.ACTION_UP) v.performClick()
                return@setOnTouchListener false
            }
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    viewModel.startGestureRecording()
                    binding.tvRoomStatus.setText(R.string.gesture_recording)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> onGestureReleased()
            }
            true
        }
        binding.btnRoomAction.setOnClickListener { onActionPressed() }

        binding.btnRoomCancel.setOnClickListener {
            viewModel.closeRoom()
            findNavController().navigateUp()
        }

        // Live counter for the host path.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectedCount.collect { count ->
                    if (isHostMode && sessionRunning) {
                        binding.tvRoomStatus.text = resources.getQuantityString(
                            R.plurals.room_host_count, count, count
                        )
                    }
                }
            }
        }

        // Session state for the guest path (terminate after first exchange).
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionState.collect { session ->
                    session ?: return@collect
                    if (!sessionRunning) return@collect
                    when (session.state) {
                        ExchangeSession.State.COMPLETED -> {
                            binding.tvRoomStatus.setText(R.string.room_guest_done)
                            sessionRunning = false
                        }
                        ExchangeSession.State.ERROR -> {
                            binding.tvRoomStatus.setText(R.string.exchange_error_generic)
                            sessionRunning = false
                        }
                        ExchangeSession.State.CANCELLED -> {
                            binding.tvRoomStatus.setText(R.string.exchange_cancelled)
                            sessionRunning = false
                        }
                        else -> { /* progressing */ }
                    }
                }
            }
        }
    }

    /**
     * Reached when a tap (rather than press-and-release) is the intended
     * action: the session is already running (close), or no gesture
     * pattern is stored (confirm-unprotected dialog). When a gesture IS
     * stored, [setOnTouchListener] on the action button handles the
     * record/validate cycle directly.
     */
    private fun onActionPressed() {
        if (sessionRunning) {
            viewModel.closeRoom()
            sessionRunning = false
            renderInitialState()
            return
        }

        if (viewModel.hasGesturePattern()) {
            // A plain tap when a gesture is configured — nudge the user to
            // press-and-hold instead. The touch listener owns the real path.
            Toast.makeText(
                requireContext(),
                R.string.room_hold_to_confirm,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // No pattern stored — explicit confirmation.
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.exchange_unprotected_title)
            .setMessage(R.string.exchange_unprotected_message)
            .setCancelable(false)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                viewModel.proceedWithoutGesture()
                launchSelectedMode()
            }
            .show()
    }

    /** Invoked when the user releases a hold-to-record action button. */
    private fun onGestureReleased() {
        val matched = viewModel.validateGestureAndUnlockService()
        if (matched) {
            failedGestureAttempts = 0
            launchSelectedMode()
        } else {
            failedGestureAttempts += 1
            val remaining = (MAX_GESTURE_ATTEMPTS - failedGestureAttempts).coerceAtLeast(0)
            if (failedGestureAttempts >= MAX_GESTURE_ATTEMPTS) {
                Toast.makeText(
                    requireContext(),
                    R.string.exchange_gesture_failed_max,
                    Toast.LENGTH_LONG
                ).show()
                findNavController().navigateUp()
            } else {
                binding.tvRoomStatus.text =
                    getString(R.string.exchange_gesture_failed_retry, remaining)
            }
        }
    }

    private fun launchSelectedMode() {
        if (isHostMode) viewModel.startHost() else viewModel.startGuest()
        sessionRunning = true
        binding.btnRoomAction.setText(R.string.room_close_action)
    }

    private fun renderInitialState() {
        if (isHostMode) {
            binding.tvRoomStatus.setText(R.string.room_host_idle_status)
            binding.tvRoomSubtitle.setText(R.string.room_host_subtitle)
            binding.btnRoomAction.setText(R.string.room_open_action)
        } else {
            binding.tvRoomStatus.setText(R.string.room_guest_idle_status)
            binding.tvRoomSubtitle.setText(R.string.room_guest_subtitle)
            binding.btnRoomAction.setText(R.string.room_join_action)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
