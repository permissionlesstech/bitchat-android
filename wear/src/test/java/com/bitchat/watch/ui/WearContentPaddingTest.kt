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
    fun `chat padding keeps responsive sides and overlay clearances`() {
        val resolved = PaddingValues(start = 10.dp, top = 18.dp, end = 12.dp, bottom = 20.dp)
            .withMinimumVerticalPadding(
                layoutDirection = LayoutDirection.Ltr,
                top = 30.dp,
                bottom = 64.dp
            )

        assertEquals(10.dp, resolved.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(12.dp, resolved.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(30.dp, resolved.calculateTopPadding())
        assertEquals(64.dp, resolved.calculateBottomPadding())
    }
}
