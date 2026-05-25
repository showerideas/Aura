package com.showerideas.aura.automotive

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/**
 * Phase 7.3 — IDLE screen for Android Auto.
 * Shows "AURA" with a "Start Exchange" action.
 */
class AuraIdleScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder("AURA — Ready to exchange")
            .setHeaderAction(Action.APP_ICON)
            .addAction(Action.Builder()
                .setTitle("Start Exchange")
                .setOnClickListener {
                    // Launch gesture auth gate then exchange
                }
                .build())
            .build()
    }
}
