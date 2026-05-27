package com.showerideas.aura.ui.settings

import android.content.Intent
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * top-level Settings screen. Surfaces:
 *  - Auth method radio group (gesture / biometric).
 *  - Data shortcuts: blocked devices, clear gesture, clear all contacts.
 *  - About: version + privacy policy link.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wireAuthSection()
        wireSecuritySection()
        wireTorSection()
        wireDataSection()
        wireWearSection()
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

    // Security section — key rotation + exchange history

    private fun wireSecuritySection() {
        // Key rotation row
        binding.rowRotateKey.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_rotate_key_confirm_title)
                .setMessage(R.string.settings_rotate_key_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_rotate_key) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val success = viewModel.rotateIdentityKey()
                        Toast.makeText(
                            requireContext(),
                            if (success) R.string.settings_rotate_key_done
                            else R.string.settings_rotate_key_error,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .show()
        }

        // Exchange history row
        binding.rowExchangeHistory.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_audit)
        }
    }


    // Tor/Orbot anonymization proxy

    private fun wireTorSection() {
        val orbotInstalled = viewModel.isOrbotInstalled

        // Update subtitle to reflect Orbot install status
        binding.tvTorProxySubtitle.text = if (orbotInstalled) {
            getString(R.string.settings_tor_proxy_subtitle)
        } else {
            getString(R.string.settings_tor_proxy_orbot_missing)
        }
        binding.switchTorProxy.isEnabled = orbotInstalled

        // Observe persisted preference and keep switch in sync
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.torProxyEnabled.collect { enabled ->
                    if (binding.switchTorProxy.isChecked != enabled) {
                        binding.switchTorProxy.isChecked = enabled
                    }
                }
            }
        }

        binding.switchTorProxy.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setTorProxyEnabled(isChecked)
        }
    }

    private fun wireDataSection() {
        binding.rowBackupRestore.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_backup)
        }
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

    // Wear OS companion pairing

    private fun wireWearSection() {
        binding.rowWearCompanion.setOnClickListener {
            WearPairingBottomSheet()
                .show(childFragmentManager, WearPairingBottomSheet.TAG)
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
        super.onDestroyView()
        _binding = null
    }
}
