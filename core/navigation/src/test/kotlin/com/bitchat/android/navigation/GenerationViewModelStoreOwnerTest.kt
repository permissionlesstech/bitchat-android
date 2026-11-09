package com.bitchat.android.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationViewModelStoreOwnerTest {

    private class Tracked : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private class FakeLifecycle : ActivityRetainedLifecycle {
        private val listeners = ArrayList<RetainedLifecycle.OnClearedListener>()
        override fun addOnClearedListener(listener: RetainedLifecycle.OnClearedListener) {
            listeners += listener
        }
        override fun removeOnClearedListener(listener: RetainedLifecycle.OnClearedListener) {
            listeners -= listener
        }
        fun clear() = listeners.toList().forEach { it.onCleared() }
    }

    private val lifecycle = FakeLifecycle()
    private val owner = GenerationViewModelStoreOwner(lifecycle)

    private fun tracked(): Tracked = ViewModelProvider.create(
        owner,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = Tracked() as T
        },
    )[Tracked::class.java]

    @Test
    fun `advance clears the old viewmodels`() {
        val old = tracked()

        owner.advance()

        assertTrue(old.cleared)
    }

    @Test
    fun `the new store is empty`() {
        val old = tracked()

        owner.advance()

        assertNotSame(old, tracked())
    }

    @Test
    fun `without an advance the store keeps its viewmodels`() {
        val kept = tracked()

        assertSame(kept, tracked())
        assertFalse(kept.cleared)
    }

    @Test
    fun `advance moves the generation and the screens key`() {
        val firstKey = owner.screensKey

        owner.advance()

        assertEquals(1, owner.generation)
        assertNotEquals(firstKey, owner.screensKey)
    }

    @Test
    fun `the first key is the same in every process so saved screens come back`() {
        assertEquals(owner.screensKey, GenerationViewModelStoreOwner(FakeLifecycle()).screensKey)
    }

    @Test
    fun `a later key is never one an earlier process saved screens under`() {
        val other = GenerationViewModelStoreOwner(FakeLifecycle())

        owner.advance()
        other.advance()

        assertEquals(owner.generation, other.generation)
        assertNotEquals(owner.screensKey, other.screensKey)
    }

    @Test
    fun `the retained lifecycle clearing clears the viewmodels`() {
        val live = tracked()

        lifecycle.clear()

        assertTrue(live.cleared)
    }
}
