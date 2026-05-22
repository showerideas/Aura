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

        // Tap-to-start: this triggers gesture record / unprotected confirm /
        // service start. Long-press-to-record is more typical, but for room
        // mode a single tap is enough — the action button title makes the
        // contract explicit ("Open room" / "Join").
        binding.btnRoomAction.setOnClickListener { onActionPressed() }
        // Hold-to-record gesture: press starts recording, release validates.
        binding.btnRoomAction.setOnLongClickListener {
            if (sessionRunning || !viewModel.hasGesturePattern()) return@setOnLongClickListener false
            viewModel.startGestureRecording()
            binding.tvRoomStatus.setText(R.string.gesture_recording)
            true
        }

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
     * Called when the user taps the action button. Branches on:
     *   - already running → close the room
     *   - gesture pattern exists → require a hold-to-record (long-press)
     *   - no gesture pattern → show the unprotected-exchange dialog
     */
    private fun onActionPressed() {
        if (sessionRunning) {
            viewModel.closeRoom()
            sessionRunning = false
            renderInitialState()
            return
        }

        if (viewModel.hasGesturePattern()) {
            // The user must hold the button to record. If they tap it short
            // we tell them how to confirm.
            val state = viewModel.gestureRecordingState.value
            if (state is com.showerideas.aura.auth.GestureAuthManager.RecordingState.Recording) {
                onGestureReleased()
                return
            }
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
