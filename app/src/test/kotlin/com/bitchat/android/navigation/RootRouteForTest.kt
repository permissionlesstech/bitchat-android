package com.bitchat.android.navigation

import com.bitchat.android.AppPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which root each app phase puts under the back stack. */
class RootRouteForTest {

    @Test
    fun everyPhase_mapsToItsRoot() {
        // A returning user's start-up, as before: CHECKING, INITIALIZING and
        // READY are chat; onboarding's steps, and an init failure, are onboarding.
        val expected = mapOf(
            AppPhase.CHECKING to ChatRoute,
            AppPhase.ONBOARDING to OnboardingRoute,
            AppPhase.INITIALIZING to ChatRoute,
            AppPhase.READY to ChatRoute,
            AppPhase.INIT_FAILED to OnboardingRoute,
        )
        assertEquals(AppPhase.entries.toSet(), expected.keys)

        AppPhase.entries.forEach { phase ->
            assertEquals(
                "root for $phase",
                expected.getValue(phase),
                rootRouteFor(phase, firstLaunch = false, retrying = false),
            )
        }
    }

    @Test
    fun checking_mapsToChat() {
        // A returning user sees chat while the checks run, never an onboarding flash.
        assertEquals(ChatRoute, rootRouteFor(AppPhase.CHECKING, firstLaunch = false, retrying = false))
    }

    @Test
    fun checkingOnFirstLaunch_mapsToOnboarding() {
        // F8: no chat before the first-launch explanation.
        assertEquals(OnboardingRoute, rootRouteFor(AppPhase.CHECKING, firstLaunch = true, retrying = false))
    }

    @Test
    fun checkingAfterTryAgain_mapsToOnboarding() {
        // F8: Try again stays on onboarding while the checks run again.
        assertEquals(OnboardingRoute, rootRouteFor(AppPhase.CHECKING, firstLaunch = false, retrying = true))
    }
}
