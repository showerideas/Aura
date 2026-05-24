package com.showerideas.aura.ui.onboarding

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.showerideas.aura.R
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.databinding.FragmentOnboardingBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Three-page onboarding flow:
 *   1. Welcome
 *   2. Mini-profile (name + email)
 *   3. Gesture enrollment — front camera shows live hand detection;
 *      holding any gesture steady for ~0.5 s auto-saves its embedding.
 */
@AndroidEntryPoint
class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnboardingViewModel by viewModels()

    private var nameField:  EditText? = null
    private var emailField: EditText? = null

    // Gesture page — cached so onPageSelected can start/stop the camera
    // without requiring the page to be re-bound.
    private var gesturePreviewRef: PreviewView? = null
    private var gesturePageWired  = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pager.adapter = OnboardingPagerAdapter()
        TabLayoutMediator(binding.pageIndicator, binding.pager) { _, _ -> }.attach()

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.btnGetStarted.visibility =
                    if (position == 2) View.VISIBLE else View.GONE

                // Start the camera only when the gesture page is actually visible.
                // ViewPager2 pre-fetches adjacent pages, so wireGesturePage() is
                // called before the user reaches page 2. Deferring the camera start
                // here avoids draining battery for a screen the user may never reach.
                if (position == 2) {
                    gesturePreviewRef?.let { preview ->
                        viewModel.startGestureCamera(viewLifecycleOwner, preview)
                    }
                } else {
                    // Stop camera as soon as user swipes away from the gesture page.
                    viewModel.stopGestureCamera()
                }
            }
        })

        binding.btnGetStarted.setOnClickListener {
            val name = nameField?.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(requireContext(), R.string.onboarding_name_required, Toast.LENGTH_SHORT).show()
                binding.pager.currentItem = 1
                return@setOnClickListener
            }
            val email = emailField?.text?.toString()?.trim().orEmpty()
            if (email.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(requireContext(), R.string.onboarding_email_invalid, Toast.LENGTH_SHORT).show()
                binding.pager.currentItem = 1
                return@setOnClickListener
            }
            viewModel.completeOnboarding(name, email)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.finishEvent.collect { done ->
                    if (done) findNavController().navigate(R.id.action_onboarding_to_home)
                }
            }
        }
    }

    override fun onDestroyView() {
        viewModel.stopGestureCamera()
        nameField        = null
        emailField       = null
        gesturePreviewRef = null
        gesturePageWired  = false
        super.onDestroyView()
        _binding = null
    }

    // -------------------------------------------------------------------------
    // Pager adapter
    // -------------------------------------------------------------------------

    private inner class OnboardingPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = position
        override fun getItemCount() = 3

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val res = when (viewType) {
                0    -> R.layout.onboarding_page_welcome
                1    -> R.layout.onboarding_page_profile
                else -> R.layout.onboarding_page_gesture
            }
            val v = LayoutInflater.from(parent.context).inflate(res, parent, false)
            v.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                1 -> {
                    nameField  = holder.itemView.findViewById(R.id.et_onboard_name)
                    emailField = holder.itemView.findViewById(R.id.et_onboard_email)
                }
                2 -> wireGesturePage(holder.itemView)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gesture enrollment page
    // -------------------------------------------------------------------------

    private fun wireGesturePage(root: View) {
        // Guard: only wire collectors and listeners once. ViewPager2 can call
        // onBindViewHolder multiple times if the page gets recycled and
        // re-bound — duplicate collectors would leak and fire redundantly.
        if (gesturePageWired) return
        gesturePageWired = true

        val statusTv    = root.findViewById<TextView>(R.id.tv_onboard_gesture_status)
        val previewView = root.findViewById<PreviewView>(R.id.gesture_preview_onboard)
        val skipTv      = root.findViewById<TextView>(R.id.btn_onboard_skip)

        // Store the PreviewView so onPageSelected can start the camera lazily.
        // We do NOT start the camera here — it will start when the user actually
        // swipes to page 2 (see the OnPageChangeCallback above).
        gesturePreviewRef = previewView

        skipTv.setOnClickListener {
            viewModel.stopGestureCamera()
            statusTv.setText(R.string.onboarding_gesture_skipped)
        }

        // Live gesture label
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveGestureState.collect { state ->
                    statusTv.text = when (state) {
                        is CameraHandEmbedder.GestureState.NoHand ->
                            getString(R.string.gesture_no_hand)
                        is CameraHandEmbedder.GestureState.Detecting ->
                            getString(
                                R.string.gesture_detecting,
                                state.gesture.emoji,
                                state.gesture.displayName,
                                (state.stability * 100).toInt()
                            )
                        is CameraHandEmbedder.GestureState.Stable ->
                            getString(R.string.gesture_stable,
                                state.gesture.emoji, state.gesture.displayName)
                        is CameraHandEmbedder.GestureState.ModelError ->
                            getString(R.string.gesture_model_error)
                    }
                }
            }
        }

        // Auto-save on first stable embedding; surface errors (e.g. liveness failures)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestureRecordingState.collect { state ->
                    when (state) {
                        is GestureAuthManager.RecordingState.Complete -> {
                            // addEnrollmentSample accumulates up to MAX_ENROLLMENT_SAMPLES
                            // raw embeddings and recomputes the centroid each time, giving
                            // better FAR/FRR than a single-sample savePattern() call.
                            val count = viewModel.addEnrollmentSample(state.pattern)
                            val max   = GestureAuthManager.MAX_ENROLLMENT_SAMPLES
                            viewModel.stopGestureCamera()
                            statusTv.text = "${getString(R.string.gesture_saved_embedding)} ($count/$max)"
                        }
                        is GestureAuthManager.RecordingState.Error -> {
                            // Show the error (e.g. "Liveness check failed — please use a live hand")
                            // then reset the pipeline so the user can try again without restarting
                            // the camera. The 2 s pause gives them time to read the message.
                            statusTv.text = state.message
                            kotlinx.coroutines.delay(2_000)
                            viewModel.resetGestureCapture()
                        }
                        else -> { /* Idle / Recording — driven by liveGestureState above */ }
                    }
                }
            }
        }
    }
}
