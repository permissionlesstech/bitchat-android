package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * About, hosted as a destination rather than as a sheet.
 *
 * It has internal tabs and stacks Debug on top of itself, which is what makes
 * it a route rather than a bottom-sheet scene.
 */
@Serializable
data object AboutRoute : NavKey

/**
 * Debug settings, a full-screen destination pushed on top of About, whose
 * Settings tab is its only entry point.
 */
@Serializable
data object DebugSettingsRoute : NavKey
