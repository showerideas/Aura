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
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
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
 * Auth: before either path actually starts the service
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
    /** True once the gesture gate is successfully cleared (or bypassed). */
    private var gestureGatePassed = false
    /** Guards against double-firing on StateFlow replay after lifecycle re-entry. */
    private var gestureValidated = false

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

        // Auth gate: camera if pattern stored, unprotected dialog otherwise.
        if (viewModel.hasGesturePattern()) {
            startGestureGate()
        }

        binding.btnRoomAction.setOnClickListener { onActionPressed() }

        binding.btnRoomCancel.setOnClickListener {
            viewModel.stopGestureCamera()
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

    // -------------------------------------------------------------------------
    // Camera gesture gate
    // -------------------------------------------------------------------------

    private fun startGestureGate() {
        binding.gestureGateSection.visibility = View.VISIBLE
        binding.btnRoomAction.isEnabled = false
        viewModel.startGestureCamera(viewLifecycleOwner, binding.gesturePreviewRoom)
        observeGestureState()
    }

    private fun observeGestureState() {
        // Live gesture label + stability progress bar in the gate section
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveGestureState.collect { state ->
                    binding.tvGestureGateStatus.text = when (state) {
                        is CameraHandEmbedder.GestureState.NoHand -> {
                            binding.pbGestureGateStability.progress = 0
                            getString(R.string.gesture_no_hand)
                        }
                        is CameraHandEmbedder.GestureState.Detecting -> {
                            binding.pbGestureGateStability.progress =
                                (state.stability * 100).toInt()
                            getString(
                                R.string.gesture_detecting,
                                state.gesture.emoji,
                                state.gesture.displayName,
                                (state.stability * 100).toInt()
                            )
                        }
                        is CameraHandEmbedder.GestureState.Stable -> {
                            binding.pbGestureGateStability.progress = 100
                            getString(R.string.gesture_stable,
                                state.gesture.emoji, state.gesture.displayName)
                        }
                        is CameraHandEmbedder.GestureState.ModelError -> {
                            binding.pbGestureGateStability.progress = 0
                            getString(R.string.gesture_model_error)
                        }
                    }
                }
            }
        }

        // Auto-validate when a stable embedding is captured.
        // gestureValidated prevents double-firing on lifecycle re-entry.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestureRecordingState.collect { state ->
                    when {
                        state is GestureAuthManager.RecordingState.Complete && !gestureValidated ->
                            onGestureComplete()
                        state is GestureAuthManager.RecordingState.Error ->
                            binding.tvGestureGateStatus.text = state.message
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun onGestureComplete() {
        gestureValidated = true
        val matched = viewModel.validateGestureAndUnlockService()
        if (matched) {
            failedGestureAttempts = 0
            gestureGatePassed = true
            binding.gestureGateSection.visibility = View.GONE
            binding.btnRoomAction.isEnabled = true
            viewModel.stopGestureCamera()
            launchSelectedMode()
        } else {
            gestureValidated = false
            failedGestureAttempts++
            val remaining = (MAX_GESTURE_ATTEMPTS - failedGestureAttempts).coerceAtLeast(0)
            if (failedGestureAttempts >= MAX_GESTURE_ATTEMPTS) {
                Toast.makeText(
                    requireContext(),
                    R.string.exchange_gesture_failed_max,
                    Toast.LENGTH_LONG
                ).show()
                viewModel.stopGestureCamera()
                findNavController().navigateUp()
            } else {
                binding.pbGestureGateStability.progress = 0
                binding.tvGestureGateStatus.text =
                    getString(R.string.exchange_gesture_failed_retry, remaining)
                viewModel.resetGestureCapture()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Action button handler (session running OR no-pattern path)
    // -------------------------------------------------------------------------

    private fun onActionPressed() {
        if (sessionRunning) {
            viewModel.closeRoom()
            sessionRunning = false
            renderInitialState()
            return
        }

        // Gesture gate already cleared — launch directly without the dialog.
        if (gestureGatePassed) {
            launchSelectedMode()
            return
        }

        // No pattern stored — explicit unprotected confirmation.
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
        viewModel.stopGestureCamera()
        super.onDestroyView()
        _binding = null
    }
}
