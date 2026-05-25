package com.showerideas.aura.automotive

import androidx.car.app.CarAppService
import androidx.car.app.HostInfo
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Phase 7.3 — Android Auto CarAppService scaffold.
 * Provides IDLE, ADVERTISING, SAS display, and COMPLETED screens.
 *
 * Note: HostValidator is implemented inline to avoid a Kotlin/KAPT issue
 * with the static ALLOW_ALL_HOSTS field in some toolchain versions.
 */
class AuraCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator = object : HostValidator() {
        override fun allowsHost(hostInfo: HostInfo): Boolean = true
    }

    override fun onCreateSession(): Session = AuraCarSession()
}
