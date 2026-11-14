package com.bitchat.android.ui

import com.bitchat.android.di.ChatSessionBuilderEntryPoint
import com.bitchat.android.di.ChatSessionComponent
import com.bitchat.android.di.ChatSessionComponentBuilder
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityComponentBuilder
import com.bitchat.android.identity.scope.IdentityEnder
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.panic.testPanicMarker
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.internal.GeneratedComponent
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionHolderTest {

    /** Which identity each session was built from, in build order. */
    private val built = ArrayList<Pair<ChatSessionComponent, IdentityComponent>>()
    private val ended = ArrayList<ChatSessionComponent>()

    /** What Hilt generates: an identity component that is its own entry points. */
    private inner class FakeIdentity : IdentityComponent, GeneratedComponent, ChatSessionBuilderEntryPoint {
        override fun chatSessionBuilder() = object : ChatSessionComponentBuilder {
            override fun build(): ChatSessionComponent =
                object : ChatSessionComponent {}.also { built += it to this@FakeIdentity }
        }
    }

    private val identity = IdentityHolder(
        Provider {
            object : IdentityComponentBuilder {
                override fun build(): IdentityComponent = FakeIdentity()
            }
        },
        IdentityEnder { emptyList() },
        testPanicMarker(),
    )

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

    private fun holder() = ChatSessionHolder(identity, lifecycle) { ended += it }

    @Test
    fun `nothing is built until current is read`() {
        val holder = holder()
        assertTrue(built.isEmpty())

        val session = holder.current

        assertEquals(listOf(session), built.map { it.first })
        assertSame(session, holder.current)
        assertEquals(0, holder.generation.value)
    }

    @Test
    fun `the session is built from the current identity`() {
        val holder = holder()
        holder.current
        assertSame(identity.current, built.single().second)
    }

    @Test
    fun `the retained lifecycle clearing ends the session`() {
        val holder = holder()
        val session = holder.current

        lifecycle.clear()

        assertEquals(listOf(session), ended)
    }

    @Test
    fun `the retained lifecycle clearing with no session built ends nothing`() {
        holder()
        lifecycle.clear()
        assertTrue(built.isEmpty())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `replace ends the session and builds the next`() {
        val holder = holder()
        val old = holder.current

        assertTrue(holder.replace())

        assertEquals(listOf(old), ended)
        assertNotSame(old, holder.current)
        assertEquals(2, built.size)
        assertEquals(1, holder.generation.value)
    }

    @Test
    fun `replace after the lifecycle cleared builds nothing and returns false`() {
        val holder = holder()
        holder.current
        lifecycle.clear()
        val generation = holder.generation.value

        assertFalse(holder.replace())

        assertEquals(1, built.size)
        assertEquals(1, ended.size)
        assertEquals(generation, holder.generation.value)
    }

    @Test
    fun `an identity replacement makes the next read build a new session`() {
        val holder = holder()
        val old = holder.current

        identity.replace { true }

        assertEquals("the read is what rebuilds", 1, built.size)
        val next = holder.current
        assertNotSame(old, next)
        assertEquals(listOf(old), ended)
        assertSame(identity.current, built.last().second)
        assertEquals(1, holder.generation.value)
        assertSame(next, holder.current)
    }

    @Test
    fun `end ends the session once and the next read builds a new one`() {
        val holder = holder()
        val old = holder.current

        holder.end()
        holder.end()

        assertEquals(listOf(old), ended)
        val next = holder.current
        assertNotSame(old, next)
        assertEquals(1, holder.generation.value)
    }

    @Test
    fun `replace after end builds one session and moves the generation once`() {
        val holder = holder()
        holder.current

        holder.end()
        identity.replace { true }
        assertTrue(holder.replace())

        assertEquals(2, built.size)
        assertEquals(1, ended.size)
        assertEquals(1, holder.generation.value)
        assertSame(identity.current, built.last().second)
    }

    @Test
    fun `a read after the lifecycle cleared gets the ended session and builds nothing`() {
        val holder = holder()
        val session = holder.current
        lifecycle.clear()

        identity.replace { true }

        assertSame(session, holder.current)
        assertEquals(1, built.size)
        assertEquals(1, ended.size)
    }

    @Test
    fun `a first read after the lifecycle cleared gets a session that has already ended`() {
        val holder = holder()
        lifecycle.clear()

        val session = holder.current

        assertEquals(listOf(session), ended)
        assertSame(session, holder.current)
        assertEquals(1, built.size)
    }

    @Test
    fun `end after the lifecycle cleared does not end the session again`() {
        val holder = holder()
        holder.current
        lifecycle.clear()

        holder.end()

        assertEquals(1, ended.size)
    }

    @Test
    fun `an ender that throws still leaves the next read a new session`() {
        var fail = true
        val holder = ChatSessionHolder(identity, lifecycle) { if (fail) error("synthetic") else ended += it }
        val old = holder.current

        assertTrue(runCatching { holder.replace() }.isFailure)
        fail = false

        assertNotSame(old, holder.current)
    }

    @Test
    fun `a new holder during a panic gets a session that has already ended`() {
        identity.current
        identity.beginEnding()
        val holder = holder()

        val session = holder.current

        assertEquals(listOf(session), ended)
        assertSame(session, holder.current)
        assertEquals(1, built.size)
    }

    @Test
    fun `a holder with a live session keeps it while the identity is ending`() {
        val holder = holder()
        val session = holder.current

        identity.beginEnding()

        assertSame(session, holder.current)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `the held back session gives way to a live one on the new identity`() {
        identity.current
        identity.beginEnding()
        val holder = holder()
        val heldBack = holder.current

        identity.replace { true }
        val next = holder.current

        assertNotSame(heldBack, next)
        assertSame(identity.current, built.last().second)
        assertEquals(listOf(heldBack), ended)
        assertEquals(1, holder.generation.value)
    }

    @Test
    fun `the held back session gives way to a live one when the panic keeps the identity`() {
        val kept = identity.current
        identity.beginEnding()
        val holder = holder()
        val heldBack = holder.current

        identity.cancelEnding()
        val next = holder.current

        assertNotSame(heldBack, next)
        assertSame(kept, built.last().second)
        assertEquals(listOf(heldBack), ended)
        assertSame(next, holder.current)
        assertEquals(1, holder.generation.value)
    }
}
