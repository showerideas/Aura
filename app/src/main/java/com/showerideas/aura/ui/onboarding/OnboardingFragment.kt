package com.showerideas.aura.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.databinding.FragmentOnboardingBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Three-page onboarding flow shown once on first install:
 *   1. Welcome — app name, tagline, brief explanation.
 *   2. Mini-profile — name (required) + email (optional).
 *   3. Gesture recording — same hold-to-record button as profile screen,
 *      with a Skip link for users who don't want auth.
 *
 * The "Get started" button on the last page persists the profile and the
 * onboarding_complete flag, then navigates to home with no back stack.
 */
@AndroidEntryPoint
class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OnboardingViewModel by viewModels()

    // We hold references to the page views so we can read the EditText
    // contents when the user taps "Get started" on the last page.
    private var nameField: EditText? = null
    private var emailField: EditText? = null

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
            viewModel.completeOnboarding(name, email)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.finishEvent.collect { done ->
                    if (done) {
                        findNavController().navigate(R.id.action_onboarding_to_home)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        nameField = null
        emailField = null
        _binding = null
    }

    // -------------------------------------------------------------------------
    // Pager adapter — keeps the three page layouts inline rather than spinning
    // up real Fragment instances. The pages are lightweight static layouts.
    // -------------------------------------------------------------------------

    private inner class OnboardingPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = position
        override fun getItemCount(): Int = 3

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layoutRes = when (viewType) {
                0 -> R.layout.onboarding_page_welcome
                1 -> R.layout.onboarding_page_profile
                else -> R.layout.onboarding_page_gesture
            }
            val v = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
            v.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                1 -> {
                    nameField = holder.itemView.findViewById(R.id.et_onboard_name)
                    emailField = holder.itemView.findViewById(R.id.et_onboard_email)
                }
                2 -> wireGesturePage(holder.itemView)
            }
        }
    }

    private fun wireGesturePage(root: View) {
        val statusTv = root.findViewById<TextView>(R.id.tv_onboard_gesture_status)
        val recordBtn = root.findViewById<View>(R.id.btn_onboard_record)
        val skipTv = root.findViewById<TextView>(R.id.btn_onboard_skip)

        recordBtn.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> viewModel.startGestureRecording()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> viewModel.stopGestureRecording()
            }
            true
        }

        skipTv.setOnClickListener {
            statusTv.setText(R.string.onboarding_gesture_skipped)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestureRecordingState.collect { state ->
                    when (state) {
                        is GestureAuthManager.RecordingState.Recording ->
                            statusTv.setText(R.string.gesture_recording)
                        is GestureAuthManager.RecordingState.Complete -> {
                            viewModel.saveGesturePattern(state.pattern)
                            statusTv.setText(R.string.gesture_saved)
                        }
                        is GestureAuthManager.RecordingState.Error ->
                            statusTv.text = state.message
                        else -> { /* Idle — leave default text */ }
                    }
                }
            }
        }
    }
}
