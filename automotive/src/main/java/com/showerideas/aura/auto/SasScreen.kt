package com.showerideas.aura.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/**
 * Android Auto SAS (Short Authentication String) verification screen.
 *
 * Displays the 6-digit SAS code that both devices compute from the key exchange.
 * The driver verbally confirms with the other person whether their codes match
 * (CONFIRM) or differ (REJECT).
 *
 * Driving-mode safety note: the driver is NOT required to type anything.
 * Two large tap targets (CONFIRM / REJECT) satisfy distraction guidelines.
 *
 * @param sasDigits The 6-digit SAS string produced by SasVerifier, e.g. "847 291".
 */
class SasScreen(
    carContext: CarContext,
    private val sasDigits: String
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val body = carContext.getString(R.string.auto_sas_body, sasDigits)
        return MessageTemplate.Builder(body)
            .setTitle(carContext.getString(R.string.auto_sas_title))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.auto_action_confirm))
                    .setBackgroundColor(CarColor.GREEN)
                    .setOnClickListener { onConfirm() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.auto_action_reject))
                    .setBackgroundColor(CarColor.RED)
                    .setOnClickListener { onReject() }
                    .build()
            )
            .build()
    }

    private fun onConfirm() {
        screenManager.pushForResult(CompletedScreen(carContext, success = true)) { _ ->
            screenManager.popToRoot()
        }
    }

    private fun onReject() {
        screenManager.pushForResult(CompletedScreen(carContext, success = false)) { _ ->
            screenManager.popToRoot()
        }
    }
}
