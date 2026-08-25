package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import com.bitchat.android.onboarding.OnboardingState

/**
 * Top-level destinations.
 *
 * Onboarding is one destination, not eight. Its steps are driven by permission
 * results and adapter state changes rather than by the user navigating, and the
 * app has never supported going back from one step to the previous one. Giving
 * each step its own entry would invent a history that does not exist.
 *
 * These live in :app because both destinations still need collaborators the
 * Activity owns. They move to :feature:<name>:api as those features become
 * modules.
 */
data object OnboardingRoute : NavKey

data object ChatRoute : NavKey

/**
 * The destination that should be at the root for a given onboarding state.
 *
 * CHECKING and INITIALIZING map to chat, matching the behaviour this replaced:
 * the app shows the chat screen while it verifies its own readiness rather than
 * flashing an onboarding step.
 */
fun rootRouteFor(state: OnboardingState): NavKey = when (state) {
    OnboardingState.CHECKING,
    OnboardingState.INITIALIZING,
    OnboardingState.COMPLETE -> ChatRoute

    else -> OnboardingRoute
}
