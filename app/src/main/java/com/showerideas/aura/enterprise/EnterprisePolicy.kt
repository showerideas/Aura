package com.showerideas.aura.enterprise

import android.content.Context
import android.content.RestrictionsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 7.4 — Enterprise managed configuration policy reader.
 * Reads MDM/EMM-pushed restrictions from RestrictionsManager and
 * publishes a StateFlow<PolicySnapshot> for app-wide enforcement.
 *
 * Managed config keys (defined in app_restrictions.xml):
 * - allowed_auth_methods: "gesture", "biometric", or "both" (default: "both")
 * - qr_relay_enabled: boolean (default: true)
 * - min_gesture_similarity: float 0.0-1.0 (default: 0.75)
 * - audit_log_retention_days: int (default: 90)
 * - force_key_rotation_days: int (default: 0 = disabled)
 */
@Singleton
class EnterprisePolicy @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class PolicySnapshot(
        val allowedAuthMethods: String = "both",
        val qrRelayEnabled: Boolean = true,
        val minGestureSimilarity: Float = 0.75f,
        val auditLogRetentionDays: Int = 90,
        val forceKeyRotationDays: Int = 0
    )

    private val _policy = MutableStateFlow(PolicySnapshot())
    val policy: StateFlow<PolicySnapshot> = _policy.asStateFlow()

    fun refresh() {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val restrictions = rm.applicationRestrictions
        _policy.value = PolicySnapshot(
            allowedAuthMethods      = restrictions.getString("allowed_auth_methods", "both") ?: "both",
            qrRelayEnabled          = restrictions.getBoolean("qr_relay_enabled", true),
            minGestureSimilarity    = restrictions.getFloat("min_gesture_similarity", 0.75f),
            auditLogRetentionDays   = restrictions.getInt("audit_log_retention_days", 90),
            forceKeyRotationDays    = restrictions.getInt("force_key_rotation_days", 0)
        )
    }
}
