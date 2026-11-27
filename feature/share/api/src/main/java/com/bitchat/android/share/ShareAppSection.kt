package com.bitchat.android.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The APK-sharing rows of the About settings card: preparing the APK, sharing
 * it over a hotspot or through another app, and their dialogs.
 */
interface ShareAppSection {
    @Composable
    fun Content(modifier: Modifier)
}
