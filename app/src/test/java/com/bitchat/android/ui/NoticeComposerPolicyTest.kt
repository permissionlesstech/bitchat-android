package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeComposerPolicyTest {
    @Test
    fun `geo offers permanent and finite board lifetimes`() {
        assertEquals(listOf(0, 1, 3, 7), NoticeComposerPolicy.expiryOptions(isGeo = true))
    }

    @Test
    fun `mesh offers only finite board lifetimes`() {
        assertEquals(listOf(1, 3, 7), NoticeComposerPolicy.expiryOptions(isGeo = false))
    }

    @Test
    fun `only permanent geo selection skips the board`() {
        assertTrue(
            NoticeComposerPolicy.isPermanentRelayOnlyGeo(
                isGeo = true,
                expiryDays = 0
            )
        )
        assertFalse(
            NoticeComposerPolicy.isPermanentRelayOnlyGeo(
                isGeo = true,
                expiryDays = 1
            )
        )
        assertFalse(
            NoticeComposerPolicy.isPermanentRelayOnlyGeo(
                isGeo = false,
                expiryDays = 1
            )
        )
    }
}
