package com.bitchat.android.navigation

import androidx.compose.runtime.Composable

/**
 * The destination the app still hosts itself. Features contribute the rest as
 * Hilt-provided [EntryProviderInstaller]s, which MainActivity installs alongside
 * this one.
 *
 * [onboarding] is the onboarding flow, which belongs to MainActivity.
 */
fun appEntries(
    onboarding: @Composable () -> Unit,
): EntryProviderInstaller = {
    entry<OnboardingRoute> { onboarding() }
}
