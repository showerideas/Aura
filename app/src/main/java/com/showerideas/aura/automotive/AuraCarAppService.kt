package com.showerideas.aura.automotive

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Phase 7.3 — Android Auto CarAppService scaffold.
 * Provides IDLE, ADVERTISING, SAS display, and COMPLETED screens.
 *
 * HostValidator.Builder(context).build() allows debug hosts by default —
 * suitable for a scaffold app. HostValidator static fields (ALLOW_ALL_HOSTS)
 * are not accessible via KAPT in this toolchain configuration.
 */
class AuraCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext).build()

    override fun onCreateSession(): Session = AuraCarSession()
}
