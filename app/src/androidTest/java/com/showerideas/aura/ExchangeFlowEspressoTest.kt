package com.showerideas.aura

import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.showerideas.aura.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PR-21: end-to-end nav-graph smoke test exercising the activation flow.
 *
 *  - Launch [MainActivity].
 *  - Assert the home Activate button is on screen.
 *  - Tap it: the [com.showerideas.aura.ui.exchange.ExchangeFragment] should be shown,
 *    identified by its tv_status TextView being displayed.
 *  - Tap btn_cancel: the user should be back on Home (btn_activate is shown again).
 *
 * The gesture / biometric gate kicks in inside ExchangeFragment, but neither
 * dialog nor prompt obscures tv_status — both are siblings in the
 * fragment_exchange layout — so the assertion remains valid in either branch.
 *
 * Flakiness note (v1.2 fix): ExchangeFragment's ViewModel launches coroutines
 * on Dispatchers.IO at startup (permission checks, BLE init). Espresso waits
 * for the main-thread Looper to idle but does NOT track IO-dispatcher work.
 * [waitForView] polls with a 5-second timeout so the assertion retries until
 * the view is laid out rather than firing immediately after the click.
 */
@RunWith(AndroidJUnit4::class)
class ExchangeFlowEspressoTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun tap_activate_shows_exchange_then_cancel_returns_home() {
        // Home: Activate button should be visible at startup.
        onView(withId(R.id.btn_activate)).check(matches(isDisplayed()))

        // Navigate to the exchange screen.
        onView(withId(R.id.btn_activate)).perform(click())

        // ExchangeFragment landmark: tv_status is its always-visible label.
        // waitForView retries until the fragment finishes laying out.
        waitForView(R.id.tv_status)

        // Cancel returns to home.
        onView(withId(R.id.btn_cancel)).perform(click())

        // Home: Activate button is showing again.
        waitForView(R.id.btn_activate)
    }

    /**
     * Polls until [id] is displayed or [timeoutMs] elapses.
     *
     * Espresso normally handles fragment transitions via its built-in idling
     * mechanism, but IO-dispatcher coroutines started by ViewModels are
     * invisible to Espresso's [UiController]. This helper bridges that gap
     * without requiring any production-code changes (e.g. no CountingIdlingResource
     * wired into the ViewModel layer).
     *
     * On final timeout the last Espresso assertion is re-thrown so the test
     * fails with a proper "View not found" message rather than a generic timeout.
     */
    private fun waitForView(@IdRes id: Int, timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e
                SystemClock.sleep(150)
            } catch (e: AssertionError) {
                lastError = e
                SystemClock.sleep(150)
            }
        }
        // Final attempt — throws the real Espresso failure message.
        lastError?.let { throw it }
        onView(withId(id)).check(matches(isDisplayed()))
    }
}
