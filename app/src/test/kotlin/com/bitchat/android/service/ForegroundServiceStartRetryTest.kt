package com.bitchat.android.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MainActivity's foreground service request (upstream's two MainActivity tests, on the helper the
 * Activity delegates to: this branch's MainActivity is a Hilt entry point, which the app's JVM
 * tests do not build).
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundServiceStartRetryTest {
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private val lifecycle = owner.lifecycle as LifecycleRegistry
    private val results = ArrayDeque<() -> Boolean>()
    private var starts = 0
    private val retry = ForegroundServiceStartRetry(lifecycle) {
        starts++
        (results.removeFirstOrNull() ?: { true })()
    }

    @Test
    fun `pending foreground request runs on initial start and subsequent restart`() {
        lifecycle.currentState = Lifecycle.State.CREATED
        retry.request()
        assertTrue(retry.pending)
        assertEquals(0, starts)

        lifecycle.currentState = Lifecycle.State.STARTED

        assertFalse("A real lifecycle start must consume the pending retry", retry.pending)
        assertEquals(1, starts)

        lifecycle.currentState = Lifecycle.State.CREATED
        retry.request()
        assertTrue(retry.pending)

        lifecycle.currentState = Lifecycle.State.STARTED

        assertFalse("A later lifecycle start must also retry", retry.pending)
        assertEquals(2, starts)
    }

    @Test
    fun `denied foreground start stays pending until an accepted retry`() {
        lifecycle.currentState = Lifecycle.State.STARTED
        results += { false }

        retry.request()
        assertTrue("Denied start must retain the activity retry", retry.pending)

        retry.request()
        assertEquals(2, starts)
        assertFalse("An accepted retry must clear the pending flag", retry.pending)
    }

    @Test
    fun `a throwing start stays pending until an accepted retry`() {
        lifecycle.currentState = Lifecycle.State.STARTED
        results += { throw SecurityException("synthetic permission revocation") }

        retry.request()
        assertTrue(retry.pending)

        lifecycle.currentState = Lifecycle.State.CREATED
        lifecycle.currentState = Lifecycle.State.STARTED

        assertEquals(2, starts)
        assertFalse(retry.pending)
    }
}
