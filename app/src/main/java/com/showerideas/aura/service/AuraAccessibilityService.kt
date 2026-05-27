package com.showerideas.aura.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import timber.log.Timber

/**
 * Accessibility-service-based volume button listener.
 *
 * This service provides a RELIABLE alternative to the MediaSession-based
 * [VolumeButtonListenerService] for OEMs (Samsung One UI, MIUI, ColorOS,
 * HyperOS) that do not route media button events to background MediaSessions.
 *
 * Why AccessibilityService?
 * The Android AccessibilityService key-event callback ([onKeyEvent]) is
 * guaranteed by the platform to receive physical key events, including volume
 * buttons, BEFORE the system handles them — on every OEM skin tested.
 * This is the same mechanism used by password managers and system-level
 * input assistants.
 *
 * Privacy note
 * AURA's AccessibilityService is declared with
 * `accessibilityEventTypes="typeAllMask (AAPT2-compatible, events ignored in onAccessibilityEvent)"` — it registers ZERO accessibility
 * events from other apps. It only intercepts key events via
 * `flagRequestFilterKeyEvents`. It cannot see the content of other apps.
 *
 * User opt-in
 * This service CANNOT be activated programmatically. The user must go to:
 *   Settings -> Accessibility -> AURA -> Enable
 * This is a platform security requirement — accessibility services always
 * require explicit user consent. [SettingsFragment] provides a direct
 * deeplink and a status indicator.
 *
 * Triple-press detection
 * Identical logic to [VolumeButtonListenerService]: three VOLUME_DOWN events
 * within [TRIPLE_PRESS_WINDOW_MS] fire [VolumeButtonListenerService.ACTION_AURA_ACTIVATE].
 * The volume change itself is NOT consumed (returns false) so the ringer
 * volume still changes normally.
 */
class AuraAccessibilityService : AccessibilityService() {

    companion object {
        private const val TRIPLE_PRESS_WINDOW_MS = 1500L
        private const val REQUIRED_PRESSES = 3

        /**
         * Returns true when this AccessibilityService is currently enabled
         * by the user in Android Accessibility settings.
         *
         * Uses [AccessibilityManager.getEnabledAccessibilityServiceList] which
         * reflects the system's real-time state — no stale caching.
         */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            return enabled.any { it.id.contains("AuraAccessibilityService", ignoreCase = true) }
        }
    }

    // Synchronised timestamp deque — onKeyEvent may arrive on a system thread.
    private val pressTimestamps = ArrayDeque<Long>(REQUIRED_PRESSES + 1)
    private val lock = Any()

    // AccessibilityService overrides

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // No accessibility events subscribed — intentionally empty.
        // accessibilityEventTypes is set to typeAllMask (AAPT2-compatible, events ignored in onAccessibilityEvent) in the XML config.
    }

    override fun onInterrupt() {
        Timber.d("AuraAccessibilityService: interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            handleVolumeDown()
        }
        // Never consume the event — volume still changes normally.
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("AuraAccessibilityService connected — key-event filtering active")
    }

    // Triple-press detection

    private fun handleVolumeDown() {
        val triggered = synchronized(lock) {
            val now = System.currentTimeMillis()
            pressTimestamps.addLast(now)

            // Drop presses outside the rolling window.
            while (pressTimestamps.isNotEmpty() &&
                now - pressTimestamps.first() > TRIPLE_PRESS_WINDOW_MS
            ) {
                pressTimestamps.removeFirst()
            }

            Timber.d("AuraA11y vol-down — ${pressTimestamps.size}/$REQUIRED_PRESSES in window")

            if (pressTimestamps.size >= REQUIRED_PRESSES) {
                pressTimestamps.clear()
                true
            } else false
        }
        if (triggered) onTriplePressDetected()
    }

    private fun onTriplePressDetected() {
        Timber.i("AuraAccessibilityService: triple-press activation!")
        sendBroadcast(Intent(VolumeButtonListenerService.ACTION_AURA_ACTIVATE).apply {
            `package` = packageName
        })
    }
}
