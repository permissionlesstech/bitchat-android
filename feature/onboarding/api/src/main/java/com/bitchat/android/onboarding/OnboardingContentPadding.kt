package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The host's scaffold padding, which onboarding applies and chat does not:
 * ChatScreen handles its own status, navigation and IME insets.
 */
val LocalOnboardingContentPadding = staticCompositionLocalOf { PaddingValues() }
