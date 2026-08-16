package com.bitchat.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownGateTest {
    @Test
    fun `shutdown is uncommitted initially`() {
        val gate = ShutdownGate()

        assertFalse(gate.isCommitted())
    }

    @Test
    fun `committed shutdown is irreversible and idempotent`() {
        val gate = ShutdownGate()

        assertTrue(gate.commit())
        assertFalse(gate.commit())

        assertTrue(gate.isCommitted())
    }
}
