package com.showerideas.aura.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import timber.log.Timber

/**
 * Android Auto / Automotive OS entry point with TTS and contact-list support.
 *
 * The head unit binds [AuraCarAppService] when the driver connects their phone
 * (Android Auto) or launches AURA from the Automotive OS app launcher.
 *
 * Navigation flow:
 *   IdleScreen → AdvertisingScreen → SasScreen → CompletedScreen
 *         └──────────────────────────────────── ContactListScreen (secondary action)
 *
 * [AutoTtsAnnouncer] announces advertising, exchange-complete, and cancel events.
 * Gesture auth is suppressed during Auto sessions via [PREF_AUTO_MODE_GESTURE_DISABLED].
 * [ContactListScreen] is accessible from [IdleScreen] as a secondary action.
 */
class AuraCarAppService : CarAppService() {

    companion object {
        /**
         * Preference key written true when the Auto session is active so that
         * GestureAuthManager skips the gesture gate and falls back to biometric.
         * Written to the app's default SharedPreferences with MODE_PRIVATE.
         */
        const val PREF_AUTO_MODE_GESTURE_DISABLED = "auto_mode_gesture_disabled"
    }

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AuraSession()
}

/**
 * Single Car App session. Owns the [AutoTtsAnnouncer] lifecycle and writes
 * the gesture-disabled flag for the duration of the Auto session.
 */
class AuraSession : Session() {

    val ttsAnnouncer: AutoTtsAnnouncer by lazy { AutoTtsAnnouncer(carContext) }

    override fun onCreateScreen(intent: Intent): Screen {
        ttsAnnouncer.init()
        setGestureAuthDisabled(true)
        Timber.i("AuraSession: Auto session started — TTS ready, gesture auth disabled")
        return IdleScreen(carContext)
    }

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        Timber.d("AuraSession: car configuration changed locale=%s",
            newConfiguration.locales.get(0))
    }

    /**
     * Re-enable gesture auth and release TTS when the head unit disconnects.
     * Car App Library does not expose a direct session-destroy callback;
     * override [onCarConfigurationChanged] or use a LifecycleObserver in
     * production to hook this cleanup.
     */
    fun releaseSession() {
        setGestureAuthDisabled(false)
        ttsAnnouncer.shutdown()
        Timber.i("AuraSession: Auto session ended — gesture auth re-enabled")
    }

    // Gesture-disabled flag

    private fun setGestureAuthDisabled(disabled: Boolean) {
        carContext
            .getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AuraCarAppService.PREF_AUTO_MODE_GESTURE_DISABLED, disabled)
            .apply()
    }
}
