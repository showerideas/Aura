package com.showerideas.aura

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.showerideas.aura.auth.BiometricAuthHelper
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests for [BiometricAuthHelper].
 *
 * These tests verify the API surface and runtime behaviour of the biometric
 * authentication helper without requiring actual biometric hardware.
 *
 * What is tested
 *  - [BiometricAuthHelper.isAvailable] returns a [Boolean] without throwing
 *    on any API level or device configuration (emulator, no hardware, locked).
 *  - The result is consistent across two calls within the same process — the
 *    BiometricManager state must not fluctuate between two synchronous reads.
 *  - On emulators (where biometric hardware is absent), [isAvailable] returns
 *    `false` — confirming the helper correctly reports unavailability instead
 *    of crashing or returning a false positive.
 *
 * What is NOT tested
 *  - The biometric prompt UI (requires real hardware + user interaction).
 *  - The `onSuccess` / `onFailure` callbacks (tested manually per MANUAL_QA_PASS.md).
 *
 * Covers AUDIT.md §2 row 16 ("Biometric unlock — wired but no instrumentation").
 */
@RunWith(AndroidJUnit4::class)
class BiometricAvailabilityTest {

    private val context get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun isAvailable_returns_without_exception() {
        // Must not throw on any device or API level.
        val result = BiometricAuthHelper.isAvailable(context)
        assertNotNull("isAvailable must return a non-null Boolean", result)
    }

    @Test
    fun isAvailable_is_consistent_across_two_calls() {
        val first  = BiometricAuthHelper.isAvailable(context)
        val second = BiometricAuthHelper.isAvailable(context)
        assert(first == second) {
            "isAvailable returned different values on two consecutive calls: $first vs $second"
        }
    }

    @Test
    fun isAvailable_returns_false_on_emulator() {
        val isEmulator = android.os.Build.FINGERPRINT.run {
            contains("generic") || contains("unknown") || startsWith("google/sdk_gphone")
        }
        if (!isEmulator) {
            // Physical device — result depends on enrolled biometrics.
            // We just confirm it doesn't crash.
            BiometricAuthHelper.isAvailable(context)
            return
        }
        // On the CI emulator, BIOMETRIC_STRONG hardware is absent →
        // BiometricManager reports BIOMETRIC_ERROR_NO_HARDWARE or
        // BIOMETRIC_ERROR_NONE_ENROLLED. Either way isAvailable is false.
        val available = BiometricAuthHelper.isAvailable(context)
        assert(!available) {
            "Expected isAvailable to return false on emulator (no biometric hardware), got true"
        }
    }

    @Test
    fun isAvailable_uses_application_context_safely() {
        // Pass both application context and a plain targetContext — both must work.
        val fromApp    = BiometricAuthHelper.isAvailable(context.applicationContext)
        val fromTarget = BiometricAuthHelper.isAvailable(context)
        assert(fromApp == fromTarget) {
            "isAvailable produced different results for applicationContext vs targetContext"
        }
    }
}
