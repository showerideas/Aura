package com.showerideas.aura

import android.app.Application
import com.showerideas.aura.BuildConfig
import com.showerideas.aura.enterprise.AuditRetentionWorker
import com.showerideas.aura.security.AppSecurityState
import com.showerideas.aura.security.BlocklistRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AuraApplication : Application() {

    // Task 65 — Hilt-injected security state; initialized before onCreate() body runs.
    @Inject lateinit var appSecurityState: AppSecurityState

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("AURA Application starting — v${BuildConfig.VERSION_NAME}")

        // Phase 8.4 — periodic remote blocklist refresh (every 24 h, network-constrained).
        BlocklistRefreshWorker.enqueue(this)

        // Phase 7.4 / H1 — periodic audit-log retention cleanup (every 24 h).
        // Retention window is controlled by the MDM policy audit_log_retention_days
        // (default 90 days). Entries older than the window are pruned from Room.
        AuditRetentionWorker.enqueue(this)

        // Task 65 — Initialize security profile from Advanced Protection + MDM policy.
        // Called with no MDM args here; NearbyExchangeService and other services call
        // appSecurityState.refresh(enterpriseMaxAttempts, ...) after EnterprisePolicy is injected.
        // On first call, reads AdvancedProtectionManager (API 36+) to build baseline profile.
        appSecurityState.refresh()
        Timber.i(
            "AppSecurityState: ap=${appSecurityState.profile.value.advancedProtectionActive} " +
            "constraints=${appSecurityState.profile.value.hasActiveConstraints}"
        )
    }
}
