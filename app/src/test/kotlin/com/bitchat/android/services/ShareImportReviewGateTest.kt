package com.bitchat.android.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareImportReviewGateTest {
    @After
    fun tearDown() {
        ShareImportReviewGate.discard()
    }

    @Test
    fun `empty shared payload is not staged`() {
        assertFalse(ShareImportReviewGate.stage(PendingSharedContent(text = "   ")))
        assertNull(ShareImportReviewGate.pending.value)
    }

    @Test
    fun `shared content requires explicit confirmation before consumption`() {
        val payload = PendingSharedContent(text = "hello from share sheet", sourcePackage = "com.example")
        assertTrue(ShareImportReviewGate.stage(payload))
        assertEquals(payload, ShareImportReviewGate.pending.value)

        val confirmed = ShareImportReviewGate.confirmAndConsume()
        assertEquals(payload, confirmed)
        assertNull(ShareImportReviewGate.pending.value)
        assertNull(ShareImportReviewGate.confirmAndConsume())
    }

    @Test
    fun `discard clears staged content without consuming it`() {
        val payload = PendingSharedContent(mediaUris = listOf("content://media/1"))
        assertTrue(ShareImportReviewGate.stage(payload))
        ShareImportReviewGate.discard()
        assertNull(ShareImportReviewGate.pending.value)
    }
}
