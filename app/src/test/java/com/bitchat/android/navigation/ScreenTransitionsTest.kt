package com.bitchat.android.navigation

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Screens slide in from the end and back out to it, mirrored in RTL. */
class ScreenTransitionsTest {

    private val width = 400

    @Test
    fun leftToRightPushesFromTheRightAndPopsBackToIt() {
        val ltr = LayoutDirection.Ltr
        assertEquals(400, ScreenTransitions.pushEnterOffset(width, ltr))
        assertEquals(-100, ScreenTransitions.pushExitOffset(width, ltr))
        assertEquals(-100, ScreenTransitions.popEnterOffset(width, ltr))
        assertEquals(400, ScreenTransitions.popExitOffset(width, ltr))
    }

    @Test
    fun rightToLeftPushesFromTheLeftAndPopsBackToIt() {
        val rtl = LayoutDirection.Rtl
        assertEquals(-400, ScreenTransitions.pushEnterOffset(width, rtl))
        assertEquals(100, ScreenTransitions.pushExitOffset(width, rtl))
        assertEquals(100, ScreenTransitions.popEnterOffset(width, rtl))
        assertEquals(-400, ScreenTransitions.popExitOffset(width, rtl))
    }
}
