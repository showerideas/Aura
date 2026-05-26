package com.showerideas.aura.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat

/**
 * Phase 7.3 — Android Auto IDLE screen.
 *
 * Shown when no exchange is in progress.  The single primary action launches
 * [AdvertisingScreen], which broadcasts the device's profile over Nearby
 * Connections (phone side) and waits for a peer to connect.
 *
 * Driving-mode UI requirements (Car App Library):
 *   • Maximum 2 primary actions (MessageTemplate limit).
 *   • Text must be concise — the system may truncate at ~30 chars on small HUs.
 *   • No launcher-style grids — MessageTemplate is the appropriate template
 *     for an action that requires no data input.
 */
class IdleScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.auto_idle_body))
            .setTitle(carContext.getString(R.string.auto_app_name))
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.auto_action_start))
                    .setBackgroundColor(CarColor.BLUE)
                    .setOnClickListener {
                        screenManager.push(AdvertisingScreen(carContext))
                    }
                    .build()
            )
            .build()
}
