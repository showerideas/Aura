package com.showerideas.aura.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import timber.log.Timber

/**
 * Phase 7.3 / G2 — Android Auto IDLE screen with biometric-only auth gate.
 *
 * • **Camera available** (Android Auto projection): navigate directly to
 *   [AdvertisingScreen] — gesture auth runs there.
 *
 * • **Camera unavailable** (Automotive OS — phone camera inaccessible):
 *   Launch [AuraBiometricAutoActivity] (transparent, phone-side BiometricPrompt).
 *   On success a broadcast arrives here and we push [AdvertisingScreen].
 *   On failure [authFailed] toggles an error body string.
 *
 * Driving-mode UI requirements (Car App Library):
 *   • Maximum 2 primary actions (MessageTemplate limit).
 *   • Text must be concise — the system may truncate at ~30 chars on small HUs.
 */
class IdleScreen(carContext: CarContext) : Screen(carContext) {

    private val cameraUnavailable: Boolean
        get() = carContext.packageManager
            .hasSystemFeature("android.hardware.type.automotive")

    private var authFailed = false

    private val biometricReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AuraBiometricAutoActivity.ACTION_BIOMETRIC_SUCCESS -> {
                    Timber.i("IdleScreen: biometric succeeded — navigating to exchange")
                    authFailed = false
                    screenManager.push(AdvertisingScreen(carContext))
                }
                AuraBiometricAutoActivity.ACTION_BIOMETRIC_FAILED -> {
                    val msg = intent.getStringExtra(AuraBiometricAutoActivity.EXTRA_ERROR_MSG)
                        ?: "Authentication failed"
                    Timber.w("IdleScreen: biometric failed — %s", msg)
                    authFailed = true
                    invalidate()
                }
            }
        }
    }

    init { registerBiometricReceiver() }

    override fun onGetTemplate(): Template {
        val body = if (authFailed)
            carContext.getString(R.string.auto_auth_failed_body)
        else
            carContext.getString(R.string.auto_idle_body)
        return MessageTemplate.Builder(body)
            .setTitle(carContext.getString(R.string.auto_app_name))
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.auto_action_start))
                    .setBackgroundColor(CarColor.BLUE)
                    .setOnClickListener { onStartClicked() }
                    .build()
            )
            .build()
    }

    private fun onStartClicked() {
        authFailed = false
        if (cameraUnavailable) {
            Timber.d("IdleScreen: camera unavailable — launching biometric gate")
            carContext.startActivity(
                Intent(carContext, AuraBiometricAutoActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } else {
            Timber.d("IdleScreen: camera available — skipping biometric gate")
            screenManager.push(AdvertisingScreen(carContext))
        }
    }

    private fun registerBiometricReceiver() {
        val filter = IntentFilter().apply {
            addAction(AuraBiometricAutoActivity.ACTION_BIOMETRIC_SUCCESS)
            addAction(AuraBiometricAutoActivity.ACTION_BIOMETRIC_FAILED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            carContext.registerReceiver(biometricReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            carContext.registerReceiver(biometricReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { carContext.unregisterReceiver(biometricReceiver) }
        catch (_: IllegalArgumentException) { /* already unregistered */ }
    }
}
