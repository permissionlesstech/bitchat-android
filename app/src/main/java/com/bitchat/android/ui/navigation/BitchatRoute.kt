package com.bitchat.android.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable


@Serializable
sealed interface BitchatRoute : NavKey {
    @Serializable
    data object Main : BitchatRoute
}
