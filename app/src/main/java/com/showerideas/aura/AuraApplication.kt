package com.showerideas.aura

import android.app.Application
import com.showerideas.aura.BuildConfig
import com.showerideas.aura.enterprise.AuditRetentionWorker
import com.showerideas.aura.security.BlocklistRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AuraApplication : Application() {

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
    }
}
