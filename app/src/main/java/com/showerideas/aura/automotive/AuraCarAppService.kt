package com.showerideas.aura.automotive

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Phase 7.3 — Android Auto CarAppService scaffold.
 * Provides IDLE, ADVERTISING, SAS display, and COMPLETED screens.
 */
class AuraCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_DEBUG_HOSTS

    override fun onCreateSession(): Session = AuraCarSession()
}
