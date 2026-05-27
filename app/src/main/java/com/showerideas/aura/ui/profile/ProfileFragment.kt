package com.showerideas.aura.ui.profile

import android.content.Intent
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.showerideas.aura.utils.AvatarUtils
import com.showerideas.aura.utils.DeeplinkUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfileViewModel by viewModels()

    // SAF image picker. The result callback can fire after a config
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

                    // Restore share-field checkboxes from the saved profile.
                    // Without this, returning to the screen always shows the
                    // layout defaults, so a save would silently clobber the
                    // user's previously chosen share preferences.
                    val shared = profile.shareFields.split(",").map { it.trim() }.toSet()
                    binding.cbSharePhone.isChecked   = "phone"       in shared
                    binding.cbShareEmail.isChecked   = "email"       in shared
                    binding.cbShareCompany.isChecked = "company"     in shared
                    binding.cbShareTitle.isChecked   = "title"       in shared
                    binding.cbShareWebsite.isChecked = "website"     in shared
                    binding.cbShareBio.isChecked     = "bio"         in shared

                    // load avatar from disk if previously saved.
                    val avatar = AvatarUtils.userAvatarFile(requireContext())
                    AvatarUtils.loadBitmap(avatar)?.let { binding.ivAvatar.setImageBitmap(it) }
                }
            }
        }

        // Camera gesture enrollment state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestureRecordingState.collect { state ->
                    when (state) {
                        is GestureAuthManager.RecordingState.Idle -> {
                            binding.btnRecordGesture.setText(R.string.profile_btn_record)
                            binding.gestureStatus.text =
                                if (viewModel.hasGesturePattern)
                                    getString(R.string.profile_gesture_status_saved_idle)
                                else
                                    getString(R.string.profile_gesture_status_none)
                            binding.gesturePreviewProfile.visibility = View.GONE
                            binding.gestureStrengthBars.visibility = View.GONE
                            binding.tvVarianceLabel.visibility = View.GONE
                            paintStrengthBars(0)
                        }
                        is GestureAuthManager.RecordingState.Recording -> {
                            binding.btnRecordGesture.setText(R.string.profile_btn_recording)
                            binding.gestureStatus.text =
                                getString(R.string.profile_gesture_status_perform)
                            binding.gesturePreviewProfile.visibility = View.VISIBLE
                            binding.gestureStrengthBars.visibility = View.VISIBLE
                            binding.tvVarianceLabel.visibility = View.VISIBLE
                        }
                        is GestureAuthManager.RecordingState.AwaitingStep2 -> {
                            // Step 1 captured — prompt the user to perform their
                            // second gesture. Camera remains running; the preview
                            // stays visible so they can see the feed.
                            binding.btnRecordGesture.setText(R.string.profile_btn_recording)
                            binding.gestureStatus.text =
                                getString(R.string.profile_gesture_status_step2)
                            binding.gesturePreviewProfile.visibility = View.VISIBLE
                            binding.gestureStrengthBars.visibility = View.VISIBLE
                            binding.tvVarianceLabel.visibility = View.VISIBLE
                            paintStrengthBars(0)
                        }
                        is GestureAuthManager.RecordingState.Complete -> {
                            // addEnrollmentSample accumulates up to MAX_ENROLLMENT_SAMPLES raw
                            // embeddings and recomputes the centroid each time, giving better
                            // FAR/FRR than a single-sample savePattern() call. Each re-record
                            // press improves accuracy without re-doing the full flow.
                            val count = viewModel.addEnrollmentSample(state.pattern)
                            val max   = GestureAuthManager.MAX_ENROLLMENT_SAMPLES
                            viewModel.stopGestureCamera()
                            binding.btnRecordGesture.setText(R.string.profile_btn_rerecord)
                            binding.gestureStatus.text =
                                "${getString(R.string.gesture_saved_embedding)} ($count/$max)"
                            binding.gesturePreviewProfile.visibility = View.GONE
                            binding.gestureStrengthBars.visibility = View.GONE
                            binding.tvVarianceLabel.visibility = View.GONE
                            paintStrengthBars(0)
                        }
                        is GestureAuthManager.RecordingState.Error -> {
                            binding.gestureStatus.text = state.message
                            viewModel.stopGestureCamera()
                            binding.gesturePreviewProfile.visibility = View.GONE
                            binding.gestureStrengthBars.visibility = View.GONE
                            binding.tvVarianceLabel.visibility = View.GONE
                            paintStrengthBars(0)
                        }
                    }
                }
            }
        }

        // Stability → strength bars (0..1 from camera confidence, mapped to 0..5 bars)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveVariance.collect { confidence ->
                    paintStrengthBars((confidence * 5).toInt().coerceIn(0, 5))
                }
            }
        }

        // Tap to start camera enrollment; camera stops automatically on Complete/Error
        binding.btnRecordGesture.setOnClickListener {
            if (viewModel.gestureRecordingState.value is GestureAuthManager.RecordingState.Idle ||
                viewModel.gestureRecordingState.value is GestureAuthManager.RecordingState.Error) {
                viewModel.startGestureCamera(viewLifecycleOwner, binding.gesturePreviewProfile)
            }
        }

        binding.btnSave.setOnClickListener { saveProfile() }

        // share card as a deep-link URL via the system share sheet.
        binding.btnShareCard.setOnClickListener {
            val profile = viewModel.profile.value ?: run {
                Toast.makeText(requireContext(), R.string.profile_saved, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = DeeplinkUtils.generateShareUrl(profile)
            val shareText = getString(R.string.share_card_text, url)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_card_chooser_title)))
        }

        // tap the avatar to launch the system picker.
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
     * paint the 5-segment strength meter. [litCount] bars become
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
        // the bars themselves are flagged importantForAccessibility=no
        // so the meter is communicated to TalkBack purely through the label.
        binding.tvVarianceLabel.contentDescription =
            getString(R.string.profile_gesture_strength_a11y, litCount)
    }

    override fun onDestroyView() {
        viewModel.stopGestureCamera()
        super.onDestroyView()
        _binding = null
    }
}
