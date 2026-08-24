package com.bitchat.watch.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WearContentPaddingTest {

    @Test
    fun `additional padding preserves scaffold insets`() {
        val resolved = PaddingValues(start = 10.dp, top = 18.dp, end = 12.dp, bottom = 20.dp)
            .withAdditionalPadding(
                layoutDirection = LayoutDirection.Ltr,
                horizontal = 10.dp,
                vertical = 8.dp
            )

        assertEquals(20.dp, resolved.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(22.dp, resolved.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(26.dp, resolved.calculateTopPadding())
        assertEquals(28.dp, resolved.calculateBottomPadding())
    }

    @Test
    fun `round screens receive an additional ten percent inset`() {
        assertEquals(20.dp, additionalRoundScreenPadding(192, isScreenRound = true))
        assertEquals(22.dp, additionalRoundScreenPadding(220, isScreenRound = true))
        assertEquals(0.dp, additionalRoundScreenPadding(192, isScreenRound = false))
    }
}
