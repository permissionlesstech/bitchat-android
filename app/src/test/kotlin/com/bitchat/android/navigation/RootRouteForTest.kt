package com.bitchat.android.navigation

import com.bitchat.android.onboarding.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks which root each onboarding state puts under the back stack today. */
class RootRouteForTest {

    @Test
    fun everyState_mapsToItsRoot() {
        val expected = mapOf(
            OnboardingState.CHECKING to ChatRoute,
            OnboardingState.BLUETOOTH_CHECK to OnboardingRoute,
            OnboardingState.LOCATION_CHECK to OnboardingRoute,
            OnboardingState.BATTERY_OPTIMIZATION_CHECK to OnboardingRoute,
            OnboardingState.PERMISSION_EXPLANATION to OnboardingRoute,
            OnboardingState.BACKGROUND_LOCATION_EXPLANATION to OnboardingRoute,
            OnboardingState.PERMISSION_REQUESTING to OnboardingRoute,
            OnboardingState.INITIALIZING to ChatRoute,
            OnboardingState.COMPLETE to ChatRoute,
            OnboardingState.ERROR to OnboardingRoute,
        )
        assertEquals(OnboardingState.entries.toSet(), expected.keys)

        OnboardingState.entries.forEach { state ->
            assertEquals("root for $state", expected.getValue(state), rootRouteFor(state))
        }
    }

    @Test
    fun checking_mapsToChat_currentBehaviour() {
        // The chat screen shows while the checks run, before onboarding has decided anything (F8).
        assertEquals(ChatRoute, rootRouteFor(OnboardingState.CHECKING))
    }
}
