package com.bitchat.android.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshServiceStartupStateTest {
    @Test
    fun `start request remains starting until component managers accept it`() {
        val state = MeshServiceStartupState()
        val attempt = requireNotNull(state.beginStart())

        assertTrue(state.isActive)
        assertTrue(state.isStarting)
        assertFalse(state.isOperational)
        assertNull(state.beginStart())

        assertTrue(state.markStarted(attempt))
        assertFalse(state.isStarting)
        assertTrue(state.isOperational)
        assertNull(state.beginStart())
    }

    @Test
    fun `asynchronous startup failure allows a fresh retry`() {
        val state = MeshServiceStartupState()
        val failedAttempt = requireNotNull(state.beginStart())

        assertTrue(state.markFailed(failedAttempt))
        assertFalse(state.isActive)

        val retry = state.beginStart()
        assertNotNull(retry)
        assertTrue(retry!! > failedAttempt)
        assertTrue(state.isStarting)
    }

    @Test
    fun `stale callbacks cannot overwrite a newer startup attempt`() {
        val state = MeshServiceStartupState()
        val first = requireNotNull(state.beginStart())
        assertTrue(state.markFailed(first))
        val second = requireNotNull(state.beginStart())

        assertFalse(state.markStarted(first))
        assertFalse(state.markFailed(first))
        assertTrue(state.markStarted(second))
        assertTrue(state.isOperational)
    }

    @Test
    fun `manager becoming inactive invalidates active mesh state`() {
        val state = MeshServiceStartupState()
        val attempt = requireNotNull(state.beginStart())
        assertTrue(state.markStarted(attempt))

        assertTrue(state.invalidateIfManagerStopped(managerActive = false))
        assertFalse(state.isActive)
        assertFalse(state.isOperational)
        assertFalse(state.invalidateIfManagerStopped(managerActive = false))
    }
}
