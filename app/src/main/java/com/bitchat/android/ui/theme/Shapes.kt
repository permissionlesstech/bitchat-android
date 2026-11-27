package com.bitchat.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Rounded Material 3 shape scale for the redesigned surface.
 *
 * There was previously no [Shapes] definition, so components fell back to Material's stock scale.
 * Defining it here is what gives buttons, cards, text fields, chips, sheets, and the FAB their
 * softer, modern corners app-wide from a single source — no per-composable shape overrides needed.
 */
val BitchatShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
