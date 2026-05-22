package com.showerideas.aura.ui.profile

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.CycleInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.databinding.FragmentProfileBinding
import com.showerideas.aura.model.GesturePattern
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Populate fields from existing profile
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profile.collect { profile ->
                    profile ?: return@collect
                    binding.etName.setText(profile.displayName)
                    binding.etPhone.setText(profile.phone)
                    binding.etEmail.setText(profile.email)
                    binding.etCompany.setText(profile.company)
                    binding.etTitle.setText(profile.title)
                    binding.etWebsite.setText(profile.website)
                    binding.etBio.setText(profile.bio)
                }
            }
        }

        // Gesture recording state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestureRecordingState.collect { state ->
                    when (state) {
                        is GestureAuthManager.RecordingState.Idle -> {
                            binding.btnRecordGesture.text = "Record Gesture"
                            binding.gestureStatus.text = if (viewModel.hasGesturePattern)
                                "Gesture saved" else "No gesture set"
                        }
                        is GestureAuthManager.RecordingState.Recording -> {
                            binding.btnRecordGesture.text = "Recording... (release to stop)"
                            binding.gestureStatus.text = "Perform your gesture now"
                        }
                        is GestureAuthManager.RecordingState.Complete -> {
                            viewModel.saveGesturePattern(state.pattern)
                            binding.btnRecordGesture.text = "Re-record Gesture"
                            binding.gestureStatus.text = "Gesture saved!"
                        }
                        is GestureAuthManager.RecordingState.Error -> {
                            binding.gestureStatus.text = state.message
                            // PR-06: shake the record button so the failure
                            // is also conveyed tactilely, not just textually.
                            shakeView(binding.btnRecordGesture)
                        }
                    }
                }
            }
        }

        binding.btnRecordGesture.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> viewModel.startGestureRecording()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> viewModel.stopGestureRecording()
            }
            true
        }

        binding.btnSave.setOnClickListener { saveProfile() }
    }

    private fun saveProfile() {
        viewModel.saveProfile(
            name = binding.etName.text.toString(),
            phone = binding.etPhone.text.toString(),
            email = binding.etEmail.text.toString(),
            company = binding.etCompany.text.toString(),
            title = binding.etTitle.text.toString(),
            website = binding.etWebsite.text.toString(),
            bio = binding.etBio.text.toString(),
            shareFields = getCheckedShareFields()
        )
        Toast.makeText(requireContext(), "Profile saved", Toast.LENGTH_SHORT).show()
    }

    private fun getCheckedShareFields(): String {
        return buildList {
            if (binding.cbSharePhone.isChecked) add("phone")
            if (binding.cbShareEmail.isChecked) add("email")
            if (binding.cbShareCompany.isChecked) add("company")
            if (binding.cbShareTitle.isChecked) add("title")
            if (binding.cbShareWebsite.isChecked) add("website")
            if (binding.cbShareBio.isChecked) add("bio")
            add("displayName")  // Always share name
        }.joinToString(",")
    }

    private fun shakeView(target: View) {
        ObjectAnimator.ofFloat(target, "translationX", 0f, 24f).apply {
            duration = 350
            interpolator = CycleInterpolator(3f)
            start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
