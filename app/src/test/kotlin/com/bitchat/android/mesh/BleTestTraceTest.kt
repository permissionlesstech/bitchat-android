package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTestTraceTest {
    @Test
    fun `run identifiers accept only bounded neutral labels`() {
        assertTrue(BleTestTrace.isSafeRunId("R40-local-01"))
        assertFalse(BleTestTrace.isSafeRunId("../private/path"))
        assertFalse(BleTestTrace.isSafeRunId(""))
        assertFalse(BleTestTrace.isSafeRunId("x".repeat(65)))
    }

    @Test
    fun `message correlation tokens are stable and do not expose the message id`() {
        val token = requireNotNull(BleTestTrace.messageToken("synthetic-message-01"))
        assertEquals(16, token.length)
        assertEquals(token, BleTestTrace.messageToken("synthetic-message-01"))
        assertNotEquals("synthetic-message-01", token)
        assertNotEquals(token, BleTestTrace.messageToken("synthetic-message-02"))
    }

    @Test
    fun `only explicit bounded test markers receive payload correlation tokens`() {
        val marker = "R40TRACE-A2B-01"
        val token = requireNotNull(BleTestTrace.testMarkerToken(marker))
        assertEquals(16, token.length)
        assertEquals(token, BleTestTrace.testMarkerToken(marker))
        assertNotEquals(marker, token)
        assertEquals(null, BleTestTrace.testMarkerToken("ordinary user message"))
        assertEquals(null, BleTestTrace.testMarkerToken("MESH" + "x".repeat(61)))
    }

    @Test
    fun `trace roles are bounded labels`() {
        assertTrue(BleTestTrace.isSafeRole("stationA"))
        assertTrue(BleTestTrace.isSafeRole("phone_2"))
        assertFalse(BleTestTrace.isSafeRole("r".repeat(17)))
        assertFalse(BleTestTrace.isSafeRole("role with spaces"))
    }
}
