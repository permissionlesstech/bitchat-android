package com.bitchat.watch.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Preserve the responsive, shape-aware padding supplied by Wear Material while allowing a
 * screen to reserve additional room for its own content or floating controls.
 */
internal fun PaddingValues.withAdditionalPadding(
    layoutDirection: LayoutDirection,
    horizontal: Dp = 0.dp,
    vertical: Dp = 0.dp
): PaddingValues {
    return PaddingValues(
        start = calculateStartPadding(layoutDirection) + horizontal,
        top = calculateTopPadding() + vertical,
        end = calculateEndPadding(layoutDirection) + horizontal,
        bottom = calculateBottomPadding() + vertical
    )
}

/**
 * Keep the responsive horizontal inset supplied by Wear Material while replacing its vertical
 * padding with screen-owned clearances. This keeps the lazy list full-screen for correct Wear
 * transformation and scroll calculations without stacking duplicate vertical insets.
 */
internal fun PaddingValues.withVerticalClearance(
    layoutDirection: LayoutDirection,
    top: Dp,
    bottom: Dp
): PaddingValues {
    return PaddingValues(
        start = calculateStartPadding(layoutDirection),
        top = top,
        end = calculateEndPadding(layoutDirection),
        bottom = bottom
    )
}

private fun PaddingValues.calculateStartPadding(layoutDirection: LayoutDirection): Dp =
    when (layoutDirection) {
        LayoutDirection.Ltr -> calculateLeftPadding(layoutDirection)
        LayoutDirection.Rtl -> calculateRightPadding(layoutDirection)
    }

private fun PaddingValues.calculateEndPadding(layoutDirection: LayoutDirection): Dp =
    when (layoutDirection) {
        LayoutDirection.Ltr -> calculateRightPadding(layoutDirection)
        LayoutDirection.Rtl -> calculateLeftPadding(layoutDirection)
    }
