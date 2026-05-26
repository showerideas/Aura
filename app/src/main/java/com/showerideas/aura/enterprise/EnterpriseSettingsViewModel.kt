package com.showerideas.aura.enterprise

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Phase 7.4 — ViewModel for [EnterpriseSettingsFragment].
 *
 * Exposes [EnterprisePolicy] directly; no mutation is possible since MDM
 * policies are system-controlled.
 */
@HiltViewModel
class EnterpriseSettingsViewModel @Inject constructor(
    val policy: EnterprisePolicy
) : ViewModel()
