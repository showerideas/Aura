package com.showerideas.aura

import android.Manifest
import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.navigation.Navigation
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.showerideas.aura.ui.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * end-to-end nav-graph smoke test exercising the activation flow.
 *
 *  - Launch [MainActivity].
 *  - Assert the home Activate button is on screen.
 *  - Tap it: the [com.showerideas.aura.ui.exchange.ExchangeFragment] should be shown,
 *    identified by its btn_cancel being displayed.
 *  - Tap btn_cancel: the user should be back on Home (btn_activate is shown again).
 *
 * Fresh-emulator note: if no gesture pattern is enrolled, ExchangeFragment shows
 * an "unprotected exchange" AlertDialog before the exchange begins. This dialog
 * steals window focus, making ExchangeFragment's own views invisible to Espresso's
 * default root matcher. The test dismisses the dialog via "Continue" before
 * asserting on the exchange fragment's views.
 *
 * Flakiness note (v1.3 fix): waitForView now catches all Throwable subclasses —
 * including RootViewWithoutFocusException — so transient window-focus changes
 * during dialog show/dismiss trigger a retry rather than an immediate failure.
 */
@RunWith(AndroidJUnit4::class)
class ExchangeFlowEspressoTest {

    @get:Rule(order = 0)
    val grantPermissionsRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
    )

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun ensureOnHome() {
        navigateToHomeIfOnOnboarding()
        waitForView(R.id.btn_activate)
    }

    @Test
    fun tap_activate_shows_exchange_then_cancel_returns_home() {
        onView(withId(R.id.btn_activate)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_activate)).perform(click())

        // On a fresh emulator with no gesture pattern enrolled, ExchangeFragment
        // shows an "unprotected exchange" AlertDialog (setCancelable=false).
        // That dialog steals window focus — Espresso picks the dialog root and
        // cannot see ExchangeFragment's own views. Dismiss it via "Continue" so
        // the exchange fragment window regains focus.
        SystemClock.sleep(300)
        try {
            onView(withText(R.string.action_continue)).perform(click())
        } catch (_: Throwable) {
            // No dialog — gesture pattern is enrolled; proceed normally.
        }

        // btn_cancel is always visible in ExchangeFragment regardless of auth path.
        waitForView(R.id.btn_cancel)

        // Cancel returns to home.
        onView(withId(R.id.btn_cancel)).perform(click())
        waitForView(R.id.btn_activate)
    }

    private fun navigateToHomeIfOnOnboarding() {
        activityRule.scenario.onActivity { activity ->
            val navController = Navigation.findNavController(activity, R.id.nav_host_fragment)
            if (navController.currentDestination?.id == R.id.onboardingFragment) {
                navController.navigate(R.id.action_onboarding_to_home)
            }
        }
    }

    /**
     * Polls until [id] is displayed or [timeoutMs] elapses.
     * Catches all Throwable — including RootViewWithoutFocusException — so
     * transient window-focus changes trigger a retry rather than a hard failure.
     */
    private fun waitForView(@IdRes id: Int, timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) {
                lastError = e
                SystemClock.sleep(150)
            }
        }
        lastError?.let { throw it }
        onView(withId(id)).check(matches(isDisplayed()))
    }
}
