package com.showerideas.aura.automotive

import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Phase 7.3 — AURA Android Auto session.
 */
class AuraCarSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen =
        AuraIdleScreen(carContext)
}
