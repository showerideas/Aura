package com.showerideas.aura.ui.enrollment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.showerideas.aura.R
import com.showerideas.aura.auth.enrollment.EnrollmentCaptureState
import com.showerideas.aura.auth.enrollment.VerificationResult
import com.showerideas.aura.databinding.FragmentGestureEnrollmentBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Task 71 — Gesture enrollment UI.
 *
 * Accessible from: Profile → Gesture Library → "Set Up Gesture".
 * Drives the full enrollment UX via [GestureEnrollmentViewModel]:
 *
 * State → UI mapping:
 *  WaitingForPalm  → "Hold your palm open and flat to begin." (pulsing cyan border)
 *  AnchorFailed    → haptic + "Start with your palm open and flat." (auto-retry 1.5s)
 *  Capturing(p)    → circular progress arc, "Hold your gesture..." (solid green border)
 *  CaptureComplete → spinner while extracting descriptors
 *  Success         → "Gesture saved." bottom sheet
 *  Failed          → Toast + back to WaitingForPalm
 *
 * See: ROADMAP §Task 71
 */
@AndroidEntryPoint
class GestureEnrollmentFragment : Fragment() {

    private var _binding: FragmentGestureEnrollmentBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GestureEnrollmentViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureEnrollmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarEnrollment.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.btnStartEnrollment.setOnClickListener { viewModel.startEnrollment() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { collectCaptureState() }
                launch { collectEnrollmentStatus() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.reset()
        _binding = null
    }

    // ── State collection ────────────────────────────────────────────────────

    private suspend fun collectCaptureState() {
        viewModel.captureState.collect { state ->
            updateCaptureUI(state)
        }
    }

    private suspend fun collectEnrollmentStatus() {
        viewModel.enrollmentStatus.collect { status ->
            when (status) {
                is EnrollmentStatus.Idle -> {
                    binding.btnStartEnrollment.isVisible = true
                    binding.progressSpinner.isVisible = false
                }
                is EnrollmentStatus.Enrolling, is EnrollmentStatus.Verifying -> {
                    binding.btnStartEnrollment.isVisible = false
                }
                is EnrollmentStatus.Success -> {
                    binding.progressSpinner.isVisible = false
                    showSuccessBottomSheet()
                }
                is EnrollmentStatus.Failed -> {
                    binding.progressSpinner.isVisible = false
                    Toast.makeText(
                        requireContext(),
                        status.reason,
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.startEnrollment()
                }
            }
        }
    }

    private fun updateCaptureUI(state: EnrollmentCaptureState) {
        when (state) {
            is EnrollmentCaptureState.WaitingForPalm -> {
                binding.tvEnrollmentHint.setText(R.string.enrollment_hint_waiting_for_palm)
                binding.tvEnrollmentHint.contentDescription =
                    getString(R.string.enrollment_hint_waiting_for_palm)
                binding.enrollmentProgressArc.root.isVisible = false
                binding.viewCameraBorder.setBackgroundResource(R.drawable.bg_camera_border_cyan)
            }
            is EnrollmentCaptureState.AnchorFailed -> {
                binding.tvEnrollmentHint.setText(R.string.enrollment_hint_anchor_failed)
                binding.tvEnrollmentHint.contentDescription =
                    getString(R.string.enrollment_hint_anchor_failed)
                binding.viewCameraBorder.setBackgroundResource(R.drawable.bg_camera_border_red)
                // Single tick haptic feedback
                binding.root.performHapticFeedback(
                    android.view.HapticFeedbackConstants.CONTEXT_CLICK
                )
            }
            is EnrollmentCaptureState.Capturing -> {
                binding.tvEnrollmentHint.setText(R.string.enrollment_hint_capturing)
                binding.tvEnrollmentHint.contentDescription =
                    getString(R.string.enrollment_hint_capturing)
                binding.enrollmentProgressArc.root.isVisible = true
                binding.enrollmentProgressArc.arcProgress.progress = (state.progressFraction * 100).toInt()
                // Announce progress for accessibility (polite)
                ViewCompat.setAccessibilityLiveRegion(
                    binding.enrollmentProgressArc.root,
                    ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
                )
                binding.viewCameraBorder.setBackgroundResource(R.drawable.bg_camera_border_green)
            }
            is EnrollmentCaptureState.CaptureComplete -> {
                binding.tvEnrollmentHint.setText(R.string.enrollment_hint_processing)
                binding.enrollmentProgressArc.root.isVisible = false
                binding.progressSpinner.isVisible = true
            }
        }
    }

    // ── Success bottom sheet ─────────────────────────────────────────────────

    private fun showSuccessBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_enrollment_success, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.btnEnrollmentDone).setOnClickListener {
            dialog.dismiss()
            findNavController().navigateUp()
        }
        sheetView.findViewById<View>(R.id.btnTryItNow).setOnClickListener {
            dialog.dismiss()
            viewModel.startVerification()
        }

        dialog.show()
    }
}
