package com.showerideas.aura.enterprise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.showerideas.aura.databinding.FragmentEnterpriseSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Enterprise Settings screen.
 *
 * Read-only display of the MDM-enforced policy values.  Administrators set
 * these via their EMM console; end users can see what is enforced but cannot
 * change them here.
 *
 * Shown only when [EnterprisePolicy.isManagedDevice] is true. The Settings
 * nav graph action `action_settings_to_enterprise` is guarded in
 * SettingsFragment before it's surfaced to users.
 */
@AndroidEntryPoint
class EnterpriseSettingsFragment : Fragment() {

    private var _binding: FragmentEnterpriseSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EnterpriseSettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnterpriseSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderPolicy(viewModel.policy)
    }

    private fun renderPolicy(policy: EnterprisePolicy) {
        binding.tvMdmStatus.text = if (policy.isManagedDevice) {
            getString(com.showerideas.aura.R.string.enterprise_managed)
        } else {
            getString(com.showerideas.aura.R.string.enterprise_unmanaged)
        }
        binding.tvMaxAttempts.text = policy.maxGestureAttempts.toString()
        binding.tvRetentionDays.text = policy.auditLogRetentionDays.toString()
        binding.tvRequireSas.text = boolText(policy.requireSasVerification)
        binding.tvBackupDisabled.text = boolText(policy.backupDisabled)
        binding.tvTorDisabled.text = boolText(policy.torProxyDisabled)
        binding.tvPinLock.text = boolText(policy.enforcePinLock)
    }

    private fun boolText(value: Boolean) = if (value) {
        getString(com.showerideas.aura.R.string.enterprise_enforced)
    } else {
        getString(com.showerideas.aura.R.string.enterprise_not_enforced)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
