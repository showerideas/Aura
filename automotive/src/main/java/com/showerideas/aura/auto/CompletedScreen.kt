package com.showerideas.aura.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/**
 * Phase 7.3 — Android Auto COMPLETED screen.
 *
 * Shown after the SAS verification step resolves (success or failure).
 * A single "Done" action pops back to [IdleScreen].
 *
 * @param success True if the exchange completed successfully, false on rejection
 *                or error.
 */
class CompletedScreen(
    carContext: CarContext,
    private val success: Boolean
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val body = if (success) {
            carContext.getString(R.string.auto_completed_success_body)
        } else {
            carContext.getString(R.string.auto_completed_failure_body)
        }
        return MessageTemplate.Builder(body)
            .setTitle(carContext.getString(R.string.auto_app_name))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.auto_action_done))
                    .setBackgroundColor(if (success) CarColor.GREEN else CarColor.RED)
                    .setOnClickListener { setResult(success); finish() }
                    .build()
            )
            .build()
    }
}
