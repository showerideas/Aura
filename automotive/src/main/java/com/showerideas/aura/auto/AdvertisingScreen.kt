package com.showerideas.aura.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/**
 * Phase 7.3 — Android Auto ADVERTISING screen.
 *
 * Shown while the phone is broadcasting its profile over Nearby Connections
 * waiting for a peer.  The only action cancels the exchange and returns to
 * [IdleScreen].
 *
 * In a full integration the screen listens to the exchange state flow via
 * [AutoStateChannel] and automatically navigates to [SasScreen] once a
 * peer connection is established.
 */
class AdvertisingScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.auto_advertising_body))
            .setTitle(carContext.getString(R.string.auto_app_name))
            .setHeaderAction(Action.BACK)
            .setLoading(true)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.auto_action_cancel))
                    .setBackgroundColor(CarColor.RED)
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()

    /**
     * Called by [AutoStateChannel] observer (wired in a future integration step)
     * when the phone reports that a peer has connected and SAS is ready.
     */
    fun onPeerConnected(sasDigits: String) {
        screenManager.push(SasScreen(carContext, sasDigits))
    }
}
