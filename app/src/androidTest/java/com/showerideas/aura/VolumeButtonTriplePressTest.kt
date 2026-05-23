package com.showerideas.aura

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.showerideas.aura.service.VolumeButtonListenerService
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Prompt-2: Instrumented test for triple-press volume-down activation.
 *
 * Uses [android.app.UiAutomation.injectInputEvent] to synthesise three
 * consecutive KEYCODE_VOLUME_DOWN key events and asserts that
 * [VolumeButtonListenerService.ACTION_AURA_ACTIVATE] is broadcast within
 * [RECEIVE_TIMEOUT_MS].
 *
 * ---
 *
 * KNOWN FAILURE MODES — documented here so CI results are interpreted correctly:
 *
 * 1. **Emulators (any API level)**: The emulator's `AudioManager` does not
 *    route media-button events to paused `MediaSession`s in the same way
 *    physical hardware does.  This test is skipped automatically on emulator
 *    builds (`Build.FINGERPRINT` contains "generic" or "unknown").
 *
 * 2. **Samsung One UI / Xiaomi MIUI / OPPO ColorOS physical devices**:
 *    The system UI on these OEM builds intercepts `KEYCODE_VOLUME_DOWN`
 *    before it reaches the Android `AudioManager` routing layer.
 *    `VolumeButtonListenerService.mediaSessionCallback.onMediaButtonEvent`
 *    is never called.  The test WILL FAIL on these devices; that is the
 *    correct result — it confirms the OEM-skin limitation documented in
 *    `03_volume_button_reality.md`.
 *
 * 3. **Other audio apps active**: If another app (Spotify, YouTube, etc.) held
 *    audio focus more recently than AURA started its MediaSession, the OS
 *    routes volume events to that app's session instead.  The test
 *    pre-starts `VolumeButtonListenerService` to acquire the session, but
 *    any background audio playing before the test may still pre-empt AURA.
 */
@RunWith(AndroidJUnit4::class)
class VolumeButtonTriplePressTest {

    companion object {
        private const val RECEIVE_TIMEOUT_MS = 1_500L
        private const val INTER_PRESS_MS = 100L
    }

    private val context: Context get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Skip on emulators — AudioManager routing behaves differently and
        // the test is expected to not work there (see KDoc above).
        val isEmulator = Build.FINGERPRINT.run {
            contains("generic") || contains("unknown") || startsWith("google/sdk_gphone")
        }
        Assume.assumeFalse(
            "VolumeButtonTriplePressTest skipped on emulator — AudioManager routing differs",
            isEmulator
        )

        // Start the service to register its MediaSession before injecting events.
        VolumeButtonListenerService.start(context)
        // Give the service time to bind and register the MediaSession.
        Thread.sleep(500)
    }

    @After
    fun tearDown() {
        VolumeButtonListenerService.stop(context)
    }

    /**
     * Injects three KEYCODE_VOLUME_DOWN key-down events within 1 s and
     * asserts that ACTION_AURA_ACTIVATE is broadcast within [RECEIVE_TIMEOUT_MS].
     *
     * Failure on a physical Pixel / AOSP device indicates a regression in
     * [VolumeButtonListenerService].
     *
     * Failure on a Samsung/MIUI/ColorOS device is EXPECTED and documents the
     * platform limitation — see the class KDoc.
     */
    @Test
    fun triplePressVolume_broadcastsActivateAction() {
        val latch = CountDownLatch(1)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                latch.countDown()
            }
        }
        val filter = IntentFilter(VolumeButtonListenerService.ACTION_AURA_ACTIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        try {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            repeat(3) {
                automation.injectInputEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN),
                    /* sync = */ true
                )
                Thread.sleep(INTER_PRESS_MS)
            }

            val received = latch.await(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!received) {
                fail(
                    "ACTION_AURA_ACTIVATE was not received within ${RECEIVE_TIMEOUT_MS}ms " +
                    "after three KEYCODE_VOLUME_DOWN injections.\n" +
                    "If this device runs Samsung One UI, Xiaomi MIUI, or OPPO ColorOS, " +
                    "this is a known OEM limitation — see 03_volume_button_reality.md."
                )
            }
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
}
