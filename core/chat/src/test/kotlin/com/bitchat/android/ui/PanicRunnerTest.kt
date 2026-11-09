package com.bitchat.android.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanicRunnerTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val runner = PanicRunner()

    @Test
    fun `a second call while one runs is ignored`() {
        val gate = CompletableDeferred<Unit>()
        var starts = 0

        assertTrue(runner.runOnce { starts++; gate.await() })
        assertFalse(runner.runOnce { starts++ })
        assertTrue(runner.isRunning)
        assertEquals(1, starts)

        gate.complete(Unit)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `a call after the last one finished runs`() {
        var starts = 0

        assertTrue(runner.runOnce { starts++ })
        assertTrue(runner.runOnce { starts++ })

        assertEquals(2, starts)
    }

    @Test
    fun `a failure ends the run and does not reach the process`() {
        assertTrue(runner.runOnce { error("synthetic") })

        assertFalse(runner.isRunning)
        assertTrue(runner.runOnce { })
    }
}
