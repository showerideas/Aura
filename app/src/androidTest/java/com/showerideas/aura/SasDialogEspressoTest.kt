package com.showerideas.aura

import android.Manifest
import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.navigation.Navigation
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import com.showerideas.aura.ui.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso tests for the SAS (Short Authentication String) verification dialog
 * in [com.showerideas.aura.ui.exchange.ExchangeFragment].
 *
 * ## Strategy
 * The SAS dialog appears when [NearbyExchangeService.sessionState] transitions
 * to [ExchangeSession.State.VERIFYING]. In production this requires a live peer
 * completing ECDH; in tests we use the companion's [NearbyExchangeService.injectTestSessionState]
 * test hook to set the state directly, bypassing the transport layer entirely.
 *
 * ## Flow
 * 1. Navigate to [ExchangeFragment] via btn_activate.
 * 2. Dismiss the "unprotected exchange" dialog if no gesture is enrolled.
 * 3. Inject a VERIFYING session with a known PIN.
 * 4. Assert the SAS dialog appears and displays the correct code.
 * 5. Verify confirm / mismatch button behaviour.
 */
@RunWith(AndroidJUnit4::class)
class SasDialogEspressoTest {

    @get:Rule(order = 0)
    val grantPermissionsRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
    )

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun navigateToExchangeFragment() {
        navigateToHomeIfOnOnboarding()
        waitForView(R.id.btn_activate)

        // Open ExchangeFragment
        onView(withId(R.id.btn_activate)).perform(click())

        // On an emulator with no gesture pattern enrolled ExchangeFragment shows
        // an "unprotected exchange" AlertDialog. Dismiss it to get btn_cancel visible.
        SystemClock.sleep(400)
        try {
            onView(withText(R.string.action_continue)).perform(click())
        } catch (_: Throwable) {
            // Gesture is enrolled — no dialog to dismiss
        }

        // ExchangeFragment landmark — confirms the screen is fully drawn
        waitForView(R.id.btn_cancel)
    }

    @After
    fun resetSessionState() {
        NearbyExchangeService.injectTestSessionState(null)
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun sas_dialog_appears_and_displays_pin() {
        injectVerifyingSession("123456")

        // Dialog title should appear
        waitForView(R.string.sas_dialog_title)

        // Custom view inside the dialog shows the pin code
        onView(withId(R.id.tv_sas_code))
            .check(matches(withText("123456")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun sas_dialog_confirm_button_dismisses_dialog() {
        injectVerifyingSession("654321")
        waitForView(R.string.sas_dialog_title)

        // Tap "Match ✓" — should dismiss the dialog
        onView(withText(R.string.sas_dialog_confirm)).perform(click())

        // After confirm, exchange fragment is still visible (service drives session forward)
        SystemClock.sleep(300)
        onView(withId(R.id.btn_cancel)).check(matches(isDisplayed()))
    }

    @Test
    fun sas_dialog_mismatch_button_navigates_back_to_home() {
        injectVerifyingSession("000000")
        waitForView(R.string.sas_dialog_title)

        // Tap "Mismatch ✗" — should abort and navigate up
        onView(withText(R.string.sas_dialog_mismatch)).perform(click())

        // After mismatch abort, navigation goes back to home (btn_activate)
        waitForView(R.id.btn_activate)
    }

    @Test
    fun sas_identicon_is_displayed_alongside_pin() {
        injectVerifyingSession("789012")
        waitForView(R.string.sas_dialog_title)

        onView(withId(R.id.iv_sas_identicon)).check(matches(isDisplayed()))
        onView(withId(R.id.tv_sas_instruction)).check(matches(isDisplayed()))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun injectVerifyingSession(pin: String) {
        activityRule.scenario.onActivity {
            NearbyExchangeService.injectTestSessionState(
                ExchangeSession(
                    sessionId = "espresso-sas-test",
                    state    = ExchangeSession.State.VERIFYING,
                    sasPin   = pin
                )
            )
        }
    }

    /**
     * Wait for a view with [id] to become visible.
     * Catches all Throwable (including RootViewWithoutFocusException) so
     * transient window-focus changes don't cause flaky failures.
     */
    private fun waitForView(id: Int, timeoutMs: Long = 6_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) { last = e; SystemClock.sleep(150) }
        }
        last?.let { throw it } ?: onView(withId(id)).check(matches(isDisplayed()))
    }

    /** Overload: wait for a view identified by its string resource. */
    private fun waitForView(stringRes: Int, timeoutMs: Long = 6_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withText(stringRes)).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) { last = e; SystemClock.sleep(150) }
        }
        last?.let { throw it } ?: onView(withText(stringRes)).check(matches(isDisplayed()))
    }

    private fun navigateToHomeIfOnOnboarding() {
        activityRule.scenario.onActivity { activity ->
            val navController = Navigation.findNavController(activity, R.id.nav_host_fragment)
            if (navController.currentDestination?.id == R.id.onboardingFragment) {
                navController.navigate(R.id.action_onboarding_to_home)
            }
        }
    }
}
