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
    val start = when (layoutDirection) {
        LayoutDirection.Ltr -> calculateLeftPadding(layoutDirection)
        LayoutDirection.Rtl -> calculateRightPadding(layoutDirection)
    }
    val end = when (layoutDirection) {
        LayoutDirection.Ltr -> calculateRightPadding(layoutDirection)
        LayoutDirection.Rtl -> calculateLeftPadding(layoutDirection)
    }
    return PaddingValues(
        start = start + horizontal,
        top = calculateTopPadding() + vertical,
        end = end + horizontal,
        bottom = calculateBottomPadding() + vertical
    )
}

internal fun PaddingValues.withMinimumVerticalPadding(
    layoutDirection: LayoutDirection,
    top: Dp,
    bottom: Dp
): PaddingValues {
    val start = when (layoutDirection) {
        LayoutDirection.Ltr -> calculateLeftPadding(layoutDirection)
        LayoutDirection.Rtl -> calculateRightPadding(layoutDirection)
    }
    val end = when (layoutDirection) {
        LayoutDirection.Ltr -> calculateRightPadding(layoutDirection)
        LayoutDirection.Rtl -> calculateLeftPadding(layoutDirection)
    }
    return PaddingValues(
        start = start,
        top = maxOf(calculateTopPadding(), top),
        end = end,
        bottom = maxOf(calculateBottomPadding(), bottom)
    )
}
