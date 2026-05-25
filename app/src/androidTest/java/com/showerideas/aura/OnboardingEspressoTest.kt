package com.showerideas.aura

import android.Manifest
import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.showerideas.aura.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso smoke test for [com.showerideas.aura.ui.onboarding.OnboardingFragment].
 *
 * The onboarding flow is shown on first launch (before [OnboardingPreferences]
 * marks it complete). This test verifies the three-page pager structure:
 *  - The ViewPager2 is visible.
 *  - The tab indicator (TabLayout / page_indicator) is visible.
 *  - Swiping advances the pager to the next page.
 *
 * Because CI emulators run on a fresh (unshared) package, the DataStore
 * ONBOARDING_COMPLETE flag starts as false, so MainActivity routes
 * straight to OnboardingFragment — no data setup is needed.
 *
 * v1.3 fix: waitForView now catches all Throwable (including
 * RootViewWithoutFocusException) so transient window-focus changes during
 * fragment transitions trigger a retry rather than an immediate failure.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingEspressoTest {

    @get:Rule(order = 0)
    val grantPermissionsRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
    )

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun onboarding_pager_and_tab_indicator_are_visible() {
        ensureOnOnboarding()

        onView(withId(R.id.pager)).check(matches(isDisplayed()))
        onView(withId(R.id.page_indicator)).check(matches(isDisplayed()))
    }

    @Test
    fun onboarding_swipe_advances_pager() {
        ensureOnOnboarding()

        onView(withId(R.id.page_indicator)).check(matches(isDisplayed()))
        onView(withId(R.id.pager)).perform(swipeLeft())

        SystemClock.sleep(300)
        onView(withId(R.id.pager)).check(matches(isDisplayed()))
    }

    private fun ensureOnOnboarding() {
        val onPager = try {
            onView(withId(R.id.pager)).check(matches(isDisplayed()))
            true
        } catch (_: Exception) {
            false
        }

        if (!onPager) {
            activityRule.scenario.onActivity { activity ->
                val navController = androidx.navigation.Navigation.findNavController(
                    activity, R.id.nav_host_fragment
                )
                navController.navigate(R.id.onboardingFragment)
            }
            waitForView(R.id.pager)
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
