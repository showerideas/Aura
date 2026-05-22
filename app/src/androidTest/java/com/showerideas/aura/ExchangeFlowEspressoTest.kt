package com.showerideas.aura

import androidx.test.espresso.Espresso.onView
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
        onView(withId(R.id.tv_status)).check(matches(isDisplayed()))

        // Cancel returns to home.
        onView(withId(R.id.btn_cancel)).perform(click())

        // Home: Activate button is showing again.
        onView(withId(R.id.btn_activate)).check(matches(isDisplayed()))
    }
}
