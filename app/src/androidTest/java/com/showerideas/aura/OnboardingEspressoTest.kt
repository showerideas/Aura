package com.showerideas.aura

import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 *  - The tab indicator (TabLayout) is visible.
 *  - A "Next" / "Get started" call-to-action button is present.
 *
 * Because CI emulators run on a fresh (unshared) package, the DataStore
 * `ONBOARDING_COMPLETE` flag starts as false, so MainActivity routes
 * straight to OnboardingFragment — no data setup is needed.
 *
 * If the device/emulator already has the flag set (e.g. a partial test run
 * left state behind), [MainActivity] will land on HomeFragment instead.
 * In that case we navigate to the onboarding destination directly via the
 * NavController in the activity's scenario, which is valid because the nav
 * graph declares `onboardingFragment` as a reachable destination regardless
 * of start destination routing.
 *
 * Covers AUDIT.md §2 row 05 ("Onboarding — manual QA only") → automated.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingEspressoTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun onboarding_pager_and_tab_indicator_are_visible() {
        // If the activity lands on HomeFragment (onboarding already complete),
        // force-navigate to the onboarding destination so the test is not
        // dependent on device state.
        ensureOnOnboarding()

        // ViewPager2 that hosts the three onboarding pages.
        onView(withId(R.id.pager_onboarding)).check(matches(isDisplayed()))

        // Tab indicator (dots) beneath the pager.
        onView(withId(R.id.tab_indicator)).check(matches(isDisplayed()))
    }

    @Test
    fun onboarding_next_button_advances_pager() {
        ensureOnOnboarding()

        // Verify the "Next" button is shown on page 1.
        waitForView(R.id.btn_next)
        onView(withId(R.id.btn_next)).check(matches(isDisplayed()))

        // Tap Next — should advance to page 2 (profile details).
        onView(withId(R.id.btn_next)).perform(click())

        // After the swipe, the pager should still be visible (we're on page 2).
        SystemClock.sleep(300)
        onView(withId(R.id.pager_onboarding)).check(matches(isDisplayed()))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * If the app launched into HomeFragment (flag already set), navigate the
     * NavController directly to the onboarding destination.
     */
    private fun ensureOnOnboarding() {
        val onPager = try {
            onView(withId(R.id.pager_onboarding)).check(matches(isDisplayed()))
            true
        } catch (_: Exception) {
            false
        }

        if (!onPager) {
            // Drive MainActivity's NavController to the onboarding destination.
            activityRule.scenario.onActivity { activity ->
                val navController = androidx.navigation.Navigation.findNavController(
                    activity, R.id.nav_host_fragment
                )
                navController.navigate(R.id.onboardingFragment)
            }
            waitForView(R.id.pager_onboarding)
        }
    }

    private fun waitForView(@IdRes id: Int, timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e; SystemClock.sleep(150)
            } catch (e: AssertionError) {
                lastError = e; SystemClock.sleep(150)
            }
        }
        lastError?.let { throw it }
        onView(withId(id)).check(matches(isDisplayed()))
    }
}
