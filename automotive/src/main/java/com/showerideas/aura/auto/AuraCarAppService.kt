package com.showerideas.aura.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Phase 7.3 — Android Auto / Automotive OS entry point.
 *
 * The head unit binds [AuraCarAppService] when the driver connects their phone
 * (Android Auto) or launches AURA from the Automotive OS app launcher.
 *
 * Navigation flow:
 *   IdleScreen → AdvertisingScreen → SasScreen → CompletedScreen
 *
 * All screens share [AutoStateChannel], which receives exchange state updates
 * from the paired phone via the Wearable Data Layer [WearPhoneBridge] channel
 * pattern (path /aura/exchange-state).
 */
class AuraCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AuraSession()
}

/**
 * Session — creates the first screen shown when the head unit connects.
 */
private class AuraSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = IdleScreen(carContext)
}
