package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Location notes, a full-screen destination.
 *
 * No argument: the notes belong to the building the device is in, which the
 * screen reads from the location manager when it opens.
 */
@Serializable
data object LocationNotesRoute : NavKey

/**
 * Location channels, a full-screen destination: it launches the geohash picker
 * for a result.
 */
@Serializable
data object LocationChannelsRoute : NavKey
