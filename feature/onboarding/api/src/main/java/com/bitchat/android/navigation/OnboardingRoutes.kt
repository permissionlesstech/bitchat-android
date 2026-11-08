package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Onboarding is one destination, not eight. Its steps are driven by permission
 * results and adapter state changes rather than by the user navigating, and the
 * app has never supported going back from one step to the previous one. Giving
 * each step its own entry would invent a history that does not exist.
 */
@Serializable
data object OnboardingRoute : NavKey
