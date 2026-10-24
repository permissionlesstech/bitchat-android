package com.bitchat.android.navigation

import com.bitchat.android.AppPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks which root each app phase puts under the back stack today. */
class RootRouteForTest {

    @Test
    fun everyPhase_mapsToItsRoot() {
        // The old onboarding states map onto these: CHECKING, INITIALIZING and
        // COMPLETE (READY) were chat; every step, and ERROR, was onboarding.
        val expected = mapOf(
            AppPhase.CHECKING to ChatRoute,
            AppPhase.ONBOARDING to OnboardingRoute,
            AppPhase.INITIALIZING to ChatRoute,
            AppPhase.READY to ChatRoute,
            AppPhase.INIT_FAILED to OnboardingRoute,
        )
        assertEquals(AppPhase.entries.toSet(), expected.keys)

        AppPhase.entries.forEach { phase ->
            assertEquals("root for $phase", expected.getValue(phase), rootRouteFor(phase))
        }
    }

    @Test
    fun checking_mapsToChat_currentBehaviour() {
        // The chat screen shows while the checks run, before onboarding has decided anything (F8).
        assertEquals(ChatRoute, rootRouteFor(AppPhase.CHECKING))
    }
}
