package com.showerideas.aura.ui.profile

import android.animation.ObjectAnimator
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.CycleInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.showerideas.aura.R
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.databinding.FragmentProfileBinding
import com.showerideas.aura.model.GesturePattern
import com.showerideas.aura.utils.AvatarUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfileViewModel by viewModels()

    // PR-10: SAF image picker. The result callback can fire after a config
    // change or detachment — use nullable context/_binding so it never NPEs.
    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        val target = AvatarUtils.userAvatarFile(ctx)
        val ok = AvatarUtils.compressFromUri(ctx, uri, target)
        if (!ok) {
            Toast.makeText(ctx, R.string.profile_avatar_too_large, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        viewModel.setAvatarUri(target.absolutePath)
        // Update the preview only if the view is still alive; otherwise the
        // Profile flow collector in onViewCreated will reload it on resume.
        _binding?.ivAvatar?.setImageBitmap(AvatarUtils.loadBitmap(target))
    }

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

                    // PR-10: load avatar from disk if previously saved.
                    val avatar = AvatarUtils.userAvatarFile(requireContext())
                    AvatarUtils.loadBitmap(avatar)?.let { binding.ivAvatar.setImageBitmap(it) }
                }
            }
        }

        // Gesture recording state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestureRecordingState.collect { state ->
                    when (state) {
                        is GestureAuthManager.RecordingState.Idle -> {
                            binding.btnRecordGesture.text = getString(R.string.profile_btn_record)
                            binding.gestureStatus.text =
                                if (viewModel.hasGesturePattern)
                                    getString(R.string.profile_gesture_status_saved_idle)
                                else
                                    getString(R.string.profile_gesture_status_none)
                            // PR-11: hide and reset bars when not recording.
                            binding.gestureStrengthBars.visibility = View.GONE
                            binding.tvVarianceLabel.visibility = View.GONE
                            paintStrengthBars(0)
                        }
                        is GestureAuthManager.RecordingState.Recording -> {
                            binding.btnRecordGesture.text =
                                getString(R.string.profile_btn_recording)
                            binding.gestureStatus.text =
                                getString(R.string.profile_gesture_status_perform)
                            // PR-11: surface the bars while the user holds the button.
                            binding.gestureStrengthBars.visibility = View.VISIBLE
                            binding.tvVarianceLabel.visibility = View.VISIBLE
                        }
                        is GestureAuthManager.RecordingState.Complete -> {
                            viewModel.saveGesturePattern(state.pattern)
                            binding.btnRecordGesture.text =
                                getString(R.string.profile_btn_rerecord)
                            binding.gestureStatus.text =
                                getString(R.string.profile_gesture_status_saved)
                            binding.gestureStrengthBars.visibility = View.GONE
                            binding.tvVarianceLabel.visibility = View.GONE
                            paintStrengthBars(0)
                        }
                        is GestureAuthManager.RecordingState.Error -> {
                            binding.gestureStatus.text = state.message
                            // PR-06: shake the record button so the failure
                            // is also conveyed tactilely, not just textually.
                            shakeView(binding.btnRecordGesture)
                            binding.gestureStrengthBars.visibility = View.GONE
                            binding.tvVarianceLabel.visibility = View.GONE
                            paintStrengthBars(0)
                        }
                    }
                }
            }
        }

        // PR-11: live-variance → strength bars. Separate collector so it can
        // tick independently of the recording-state machine above.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveVariance.collect { variance ->
                    val litCount = when {
                        variance < 0.15f -> 0
                        variance < 0.35f -> 1
                        variance < 0.60f -> 2
                        variance < 1.00f -> 3
                        variance < 2.00f -> 4
                        else             -> 5
                    }
                    paintStrengthBars(litCount)
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

        // PR-10: tap the avatar to launch the system picker.
        binding.ivAvatar.setOnClickListener { pickAvatarLauncher.launch("image/*") }
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
        Toast.makeText(requireContext(), R.string.profile_saved, Toast.LENGTH_SHORT).show()
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

    /**
     * PR-11: paint the 5-segment strength meter. [litCount] bars become
     * aura_purple, the rest revert to surface_variant. Tint via setColorFilter
     * on the shared drawable would mutate a single instance for all bars, so
     * we use setBackgroundColor on the per-bar View instead — the rounded
     * corners come from the drawable's initial layer.
     */
    private fun paintStrengthBars(litCount: Int) {
        val ctx = context ?: return
        val binding = _binding ?: return
        val lit = ContextCompat.getColor(ctx, R.color.aura_purple)
        val unlit = ContextCompat.getColor(ctx, R.color.surface_variant)
        val bars = listOf(
            binding.gestureBar1, binding.gestureBar2, binding.gestureBar3,
            binding.gestureBar4, binding.gestureBar5
        )
        bars.forEachIndexed { index, view ->
            // Re-apply the rounded background then tint it so we keep the
            // shape but get the correct fill colour.
            view.background = ContextCompat.getDrawable(ctx, R.drawable.gesture_strength_bar)
                ?.mutate()?.apply {
                    setColorFilter(
                        if (index < litCount) lit else unlit,
                        PorterDuff.Mode.SRC_IN
                    )
                }
        }
        // PR-17: the bars themselves are flagged importantForAccessibility=no
        // so the meter is communicated to TalkBack purely through the label.
        binding.tvVarianceLabel.contentDescription =
            getString(R.string.profile_gesture_strength_a11y, litCount)
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
