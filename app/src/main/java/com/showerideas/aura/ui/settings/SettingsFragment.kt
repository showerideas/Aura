package com.showerideas.aura.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.showerideas.aura.BuildConfig
import com.showerideas.aura.R
import com.showerideas.aura.auth.BiometricAuthHelper
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.databinding.FragmentSettingsBinding
import com.showerideas.aura.service.VolumeButtonListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * top-level Settings screen. Surfaces:
 *  - Auth method radio group (gesture / biometric — ).
 *  - Background-activation switch (drives [VolumeButtonListenerService]).
 *  - reliability warning banner + "Test triple-press now" row.
 *  - Data shortcuts: blocked devices, clear gesture, clear all contacts.
 *  - About: version + privacy policy link.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    /**
     * ephemeral broadcast receiver registered during the 3-second
     * "Test triple-press now" window. Unregistered immediately on receipt or
     * when the window expires to avoid leaking a receiver.
     */
    private var volumeTestReceiver: BroadcastReceiver? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wireAuthSection()
        wireActivationSection()
        wireDataSection()
        wireAboutSection()
    }

    private fun wireAuthSection() {
        // Disable the biometric option entirely when the device has no
        // hardware enrolled. The label keeps reading "Biometric" but the
        // subtitle becomes visible explaining the situation.
        val biometricAvailable = BiometricAuthHelper.isAvailable(requireContext())
        binding.rbAuthBiometric.isEnabled = biometricAvailable
        binding.tvBiometricSubtitle.visibility =
            if (biometricAvailable) View.GONE else View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authMethod.collect { method ->
                    binding.rbAuthGesture.isChecked =
                        method == AuthPreferences.METHOD_GESTURE
                    binding.rbAuthBiometric.isChecked =
                        method == AuthPreferences.METHOD_BIOMETRIC
                }
            }
        }
        binding.rgAuthMethod.setOnCheckedChangeListener { _, checkedId ->
            val method = when (checkedId) {
                R.id.rb_auth_biometric -> AuthPreferences.METHOD_BIOMETRIC
                else -> AuthPreferences.METHOD_GESTURE
            }
            viewModel.setAuthMethod(method)
        }
    }

    private fun wireActivationSection() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bgActivationEnabled.collect { enabled ->
                    if (binding.switchBgActivation.isChecked != enabled) {
                        binding.switchBgActivation.isChecked = enabled
                    }
                    // show / hide the reliability warning and test button
                    // so users understand the OEM-skin limitation whenever the
                    // feature is enabled.
                    val warningVisibility = if (enabled) View.VISIBLE else View.GONE
                    binding.tvVolumeWakeWarning.visibility = warningVisibility
                    binding.rowTestVolumePress.visibility = warningVisibility
                }
            }
        }
        binding.switchBgActivation.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBgActivation(isChecked)
            if (isChecked) {
                VolumeButtonListenerService.start(requireContext())
            } else {
                VolumeButtonListenerService.stop(requireContext())
            }
        }

        // "Test triple-press now" — open a 3-second window and
        // register a one-shot receiver. Toast success/fail, then clean up.
        binding.rowTestVolumePress.setOnClickListener {
            startVolumeWakeTest()
        }
    }

    /**
     * Opens a 3-second window during which the user triple-presses
     * the volume-down button. A [BroadcastReceiver] listens for
     * [VolumeButtonListenerService.ACTION_AURA_ACTIVATE]. On receipt a success
     * Toast is shown. If 3 s elapses without a broadcast, a failure Toast is shown.
     *
     * This gives the user immediate, honest feedback about whether their device's
     * OEM skin routes media buttons through to AURA's MediaSession.
     */
    private fun startVolumeWakeTest() {
        // Unregister any stale receiver from a previous test that didn't clean up.
        volumeTestReceiver?.let {
            try { requireContext().unregisterReceiver(it) } catch (_: Exception) {}
        }

        Toast.makeText(requireContext(), R.string.settings_volume_test_listening, Toast.LENGTH_SHORT).show()
        Timber.d("Volume wake test window open — listening for ACTION_AURA_ACTIVATE")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Timber.i("Volume wake test: broadcast received — device is compatible")
                Toast.makeText(requireContext(), R.string.settings_volume_test_success, Toast.LENGTH_LONG).show()
                unregisterSelf()
            }
        }
        volumeTestReceiver = receiver

        val filter = IntentFilter(VolumeButtonListenerService.ACTION_AURA_ACTIVATE)
        requireContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Close the window after 3 s regardless of whether we received the broadcast.
        viewLifecycleOwner.lifecycleScope.launch {
            delay(3_000L)
            val still = volumeTestReceiver
            if (still != null) {
                Timber.d("Volume wake test: timeout — broadcast not received")
                Toast.makeText(requireContext(), R.string.settings_volume_test_fail, Toast.LENGTH_LONG).show()
                try { requireContext().unregisterReceiver(still) } catch (_: Exception) {}
                volumeTestReceiver = null
            }
        }
    }

    private fun BroadcastReceiver.unregisterSelf() {
        volumeTestReceiver = null
        try { requireContext().unregisterReceiver(this) } catch (_: Exception) {}
    }

    private fun wireDataSection() {
        binding.rowBlockedDevices.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_blocked_devices)
        }
        binding.rowClearGesture.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_clear_gesture_title)
                .setMessage(R.string.settings_clear_gesture_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_clear_gesture_confirm) { _, _ ->
                    viewModel.clearGesture()
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_clear_gesture_done,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .show()
        }
        binding.rowClearContacts.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val count = viewModel.contactCount()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.settings_clear_contacts_title, count))
                    .setMessage(R.string.settings_clear_contacts_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.settings_clear_contacts_confirm) { _, _ ->
                        viewModel.clearAllContacts()
                        Toast.makeText(
                            requireContext(),
                            R.string.settings_clear_contacts_done,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            }
        }
    }

    private fun wireAboutSection() {
        binding.tvVersion.text = BuildConfig.VERSION_NAME
        binding.rowPrivacy.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_url))))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_privacy_fallback, getString(R.string.privacy_url)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        // always clean up the test receiver when the fragment tears down
        volumeTestReceiver?.let {
            try { requireContext().unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeTestReceiver = null
        super.onDestroyView()
        _binding = null
    }
}
