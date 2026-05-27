package com.showerideas.aura.enterprise

import android.content.Context
import android.content.RestrictionsManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-touch enrollment for MDM/EMM-provisioned devices.
 *
 * When an IT administrator deploys AURA via an EMM console (e.g. VMware Workspace ONE,
 * Microsoft Intune, Google Endpoint Management), they can pre-fill the user's profile
 * via managed app configuration. This manager reads those pre-provisioned fields and
 * populates the in-app profile on first launch — the user never has to type their name
 * or email, and the profile matches the corporate directory.
 *
 * Pre-provisioned fields (set in app_restrictions.xml)
 *
 * | MDM key                  | Profile field   | Description                          |
 * |--------------------------|-----------------|--------------------------------------|
 * | `pre_fill_display_name`  | displayName     | Full name from AD/LDAP               |
 * | `pre_fill_email`         | email           | Corporate email                      |
 * | `pre_fill_org`           | organization    | Department or company name           |
 * | `pre_fill_job_title`     | jobTitle        | Job title                            |
 * | `pre_fill_phone`         | phone           | Work phone number                    |
 * | `pre_fill_profile_type`  | profileType     | "WORK" or "PERSONAL" (default: WORK) |
 *
 * Fields not set by the MDM are left blank for the user to fill in manually.
 *
 * Enrollment flow
 * 1. Device is factory-reset and enrolled via zero-touch or QR code provisioning.
 * 2. MDM pushes managed config with [PRE_FILL_*] keys.
 * 3. AURA starts → MainActivity calls [applyIfNotEnrolled].
 * 4. [applyIfNotEnrolled] detects pre-filled values and creates the profile.
 * 5. User lands on the home screen with their profile already populated.
 */
@Singleton
class ZeroTouchEnrollmentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val enterprisePolicy: EnterprisePolicy
) {
    companion object {
        // Pre-fill keys (must match app_restrictions.xml)
        const val KEY_PRE_FILL_NAME         = "pre_fill_display_name"
        const val KEY_PRE_FILL_EMAIL        = "pre_fill_email"
        const val KEY_PRE_FILL_ORG          = "pre_fill_org"
        const val KEY_PRE_FILL_JOB_TITLE    = "pre_fill_job_title"
        const val KEY_PRE_FILL_PHONE        = "pre_fill_phone"
        const val KEY_PRE_FILL_PROFILE_TYPE = "pre_fill_profile_type"
    }

    /**
     * Pre-provisioned profile fields from MDM.
     * Returns null for any field that was not configured by the administrator.
     */
    data class PreProvisionedProfile(
        val displayName: String?,
        val email: String?,
        val organization: String?,
        val jobTitle: String?,
        val phone: String?,
        val profileType: String?
    ) {
        /** True if any field is non-null — profile pre-fill is active. */
        val hasPrefilledData: Boolean
            get() = listOf(displayName, email, organization, jobTitle, phone).any { !it.isNullOrBlank() }
    }

    /**
     * Read the MDM pre-provisioned profile from managed app restrictions.
     * Returns null if no pre-fill configuration is present.
     */
    fun readPreProvisionedProfile(): PreProvisionedProfile? {
        if (!enterprisePolicy.isManagedDevice) {
            Timber.d("ZeroTouchEnrollment: device not managed — skipping")
            return null
        }
        val restrictions = context.getSystemService<RestrictionsManager>()
            ?.applicationRestrictions ?: return null

        val profile = PreProvisionedProfile(
            displayName = restrictions.getString(KEY_PRE_FILL_NAME)?.takeIf { it.isNotBlank() },
            email       = restrictions.getString(KEY_PRE_FILL_EMAIL)?.takeIf { it.isNotBlank() },
            organization = restrictions.getString(KEY_PRE_FILL_ORG)?.takeIf { it.isNotBlank() },
            jobTitle    = restrictions.getString(KEY_PRE_FILL_JOB_TITLE)?.takeIf { it.isNotBlank() },
            phone       = restrictions.getString(KEY_PRE_FILL_PHONE)?.takeIf { it.isNotBlank() },
            profileType = restrictions.getString(KEY_PRE_FILL_PROFILE_TYPE)
        )

        return if (profile.hasPrefilledData) {
            Timber.i("ZeroTouchEnrollment: pre-fill data present — name=${profile.displayName}, email=${profile.email}")
            profile
        } else {
            Timber.d("ZeroTouchEnrollment: managed device but no pre-fill keys set")
            null
        }
    }
}
