package com.bitchat.watch.ui

import com.bitchat.watch.ui.theme.ChatVisualTokens
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearDisplayGeometryTest {
    @Test
    fun `header band corners stay inside the circle at supported text sizes`() {
        for (diameter in listOf(192f, 228f, 240f)) {
            for (scale in listOf(0.94f, 1f, 1.24f, 1.3f)) {
                val lineHeight = 15f * 1.3f * scale
                val top = 12f + (maxOf(48f, lineHeight) - lineHeight) / 2f
                val width = roundBandWidth(diameter, diameter, top, top + lineHeight)
                val radius = diameter / 2f
                for (y in listOf(top, top + lineHeight)) {
                    assertTrue((width / 2).pow(2) + (y - radius).pow(2) <= radius.pow(2) + 0.01f)
                }
            }
        }
    }

    @Test
    fun `out of display bands have no usable width`() {
        assertEquals(0f, roundBandWidth(192f, 192f, -1f, 20f))
        assertEquals(0f, roundBandWidth(192f, 192f, 180f, 193f))
    }

    @Test
    fun `image and recording safe square fits all four corners`() {
        for (diameter in listOf(192f, 228f, 240f)) {
            val side = roundContentSide(diameter, diameter)
            assertTrue(side > 0)
            assertTrue(2 * (side / 2).pow(2) < (diameter / 2).pow(2))
        }
    }

    @Test
    fun `essential shared text is at least twelve sp`() {
        assertTrue(ChatVisualTokens.SystemActionStyle.fontSize.value >= 12f)
        assertTrue(ChatVisualTokens.MessageBodyStyle.fontSize.value >= 12f)
        assertTrue(ChatVisualTokens.SenderStyle.fontSize.value >= 12f)
        assertTrue(ChatVisualTokens.TimestampStyle.fontSize.value >= 10f)
    }
}
