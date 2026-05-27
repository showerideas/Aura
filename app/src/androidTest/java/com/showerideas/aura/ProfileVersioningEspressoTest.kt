package com.showerideas.aura

import android.Manifest
import android.os.SystemClock
import androidx.navigation.Navigation
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import com.showerideas.aura.ui.MainActivity
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying that the "Card updated" Snackbar
 * appears when a completed exchange session has [ExchangeSession.profileVersionBumped] = true.
 *
 * Strategy mirrors [SasDialogEspressoTest]: we inject a pre-built
 * [ExchangeSession] via [NearbyExchangeService.injectTestSessionState] and
 * observe the resulting UI change — no live peer required.
 *
 * The Snackbar text is R.string.contact_card_updated:
 *   "%1$s updated their card since your last exchange."
 */
@RunWith(AndroidJUnit4::class)
class ProfileVersioningEspressoTest {

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

        onView(withId(R.id.btn_activate)).perform(click())

        // Dismiss "unprotected exchange" dialog if no gesture is enrolled
        SystemClock.sleep(400)
        try {
            onView(withText(R.string.action_continue)).perform(click())
        } catch (_: Throwable) {
            // Gesture enrolled — no dialog
        }

        waitForView(R.id.btn_cancel)
    }

    @After
    fun resetSessionState() {
        NearbyExchangeService.injectTestSessionState(null)
    }

    // Tests

    @Test
    fun profileVersionBump_showsCardUpdatedSnackbar() {
        // Inject a COMPLETED session with profileVersionBumped = true
        // and a receivedContact so the name is included in the Snackbar text
        activityRule.scenario.onActivity {
            NearbyExchangeService.injectTestSessionState(
                ExchangeSession(
                    sessionId           = "espresso-phase67-test",
                    state               = ExchangeSession.State.COMPLETED,
                    profileVersionBumped = true,
                    receivedContact     = Contact(
                        id          = "espresso-contact-67",
                        displayName = "Alice Test"
                    )
                )
            )
        }

        // Wait for the Snackbar to appear and assert it contains "updated their card"
        // R.string.contact_card_updated = "%1$s updated their card since your last exchange."
        waitForSnackbar("updated their card")
    }

    @Test
    fun noProfileVersionBump_noCardUpdatedSnackbar() {
        // A COMPLETED session without profileVersionBumped must NOT show the Snackbar
        activityRule.scenario.onActivity {
            NearbyExchangeService.injectTestSessionState(
                ExchangeSession(
                    sessionId           = "espresso-phase67-no-bump",
                    state               = ExchangeSession.State.COMPLETED,
                    profileVersionBumped = false,
                    receivedContact     = Contact(
                        id          = "espresso-contact-67b",
                        displayName = "Bob Test"
                    )
                )
            )
        }

        // Snackbar must NOT appear — wait briefly and confirm absence
        SystemClock.sleep(1_500)
        try {
            onView(withText(containsString("updated their card"))).check(matches(isDisplayed()))
            throw AssertionError("Snackbar should NOT appear when profileVersionBumped = false")
        } catch (e: Throwable) {
            if (e is AssertionError && e.message?.contains("should NOT") == true) throw e
            // Expected — Snackbar not present
        }
    }

    // Helpers

    private fun waitForSnackbar(text: String, timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withText(containsString(text))).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) { last = e; SystemClock.sleep(150) }
        }
        last?.let { throw it } ?: onView(withText(containsString(text))).check(matches(isDisplayed()))
    }

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

    private fun navigateToHomeIfOnOnboarding() {
        activityRule.scenario.onActivity { activity ->
            val navController = Navigation.findNavController(activity, R.id.nav_host_fragment)
            if (navController.currentDestination?.id == R.id.onboardingFragment) {
                navController.navigate(R.id.action_onboarding_to_home)
            }
        }
    }
}
