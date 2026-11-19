package com.bitchat.android.identity.scope

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityHolderTest {

    private val builds = AtomicInteger()
    private val log = CopyOnWriteArrayList<String>()
    private var failedNames: List<String> = emptyList()

    private val builder = Provider<IdentityComponentBuilder> {
        object : IdentityComponentBuilder {
            override fun build(): IdentityComponent {
                builds.incrementAndGet()
                log += "build"
                return object : IdentityComponent {}
            }
        }
    }

    private val ender = IdentityEnder {
        log += "end"
        failedNames
    }

    private fun holder() = IdentityHolder(builder, ender)

    @Test
    fun `nothing is built until current is read`() {
        holder()
        assertEquals(0, builds.get())
    }

    @Test
    fun `current is built once and stays the same`() {
        val holder = holder()
        assertSame(holder.current, holder.current)
        assertEquals(1, builds.get())
    }

    @Test
    fun `concurrent first reads build one identity`() {
        val holder = holder()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val reads = (1..threads).map {
                pool.submit<IdentityComponent> {
                    start.await()
                    holder.current
                }
            }
            start.countDown()
            val seen = reads.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(seen.all { it === seen.first() })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, builds.get())
    }

    @Test
    fun `replace builds a new component and bumps the generation`() {
        val holder = holder()
        val first = holder.current
        assertEquals(0, holder.generation.value)

        holder.replace()

        assertNotSame(first, holder.current)
        assertSame(holder.current, holder.current)
        assertEquals(1, holder.generation.value)
        assertEquals(2, builds.get())
    }

    @Test
    fun `replace ends the old component before building the next`() {
        val holder = holder()
        holder.current
        log.clear()

        holder.replace()

        assertEquals(listOf("end", "build"), log.toList())
    }

    @Test
    fun `replace before any read builds one and ends none`() {
        val holder = holder()

        holder.replace()

        assertEquals(listOf("build"), log.toList())
        assertEquals(1, builds.get())
        assertEquals(1, holder.generation.value)
    }

    @Test
    fun `a reader during replace gets the new component`() {
        val endStarted = CountDownLatch(1)
        val releaseEnd = CountDownLatch(1)
        val blockingEnder = IdentityEnder {
            endStarted.countDown()
            releaseEnd.await(10, TimeUnit.SECONDS)
            emptyList()
        }
        val holder = IdentityHolder(builder, blockingEnder)
        val old = holder.current
        val pool = Executors.newFixedThreadPool(2)
        try {
            val replacing = pool.submit { holder.replace() }
            assertTrue(endStarted.await(10, TimeUnit.SECONDS))
            val reading = pool.submit<IdentityComponent> { holder.current }
            releaseEnd.countDown()
            replacing.get(10, TimeUnit.SECONDS)
            val read = reading.get(10, TimeUnit.SECONDS)
            assertNotSame(old, read)
            assertSame(holder.current, read)
        } finally {
            releaseEnd.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `failed cleanup names come back from replace`() {
        val holder = holder()
        holder.current
        failedNames = listOf("relay", "notifications")

        assertEquals(listOf("relay", "notifications"), holder.replace())
    }

    @Test
    fun `a build that fails during replace leaves the next read to rebuild`() {
        var failBuild = false
        val built = ArrayList<IdentityComponent>()
        val ended = ArrayList<IdentityComponent>()
        val holder = IdentityHolder(
            Provider {
                object : IdentityComponentBuilder {
                    override fun build(): IdentityComponent {
                        check(!failBuild) { "synthetic build failure" }
                        return object : IdentityComponent {}.also { built += it }
                    }
                }
            },
            IdentityEnder { component ->
                ended += component
                emptyList()
            },
        )
        val first = holder.current
        failBuild = true

        assertThrows(IllegalStateException::class.java) { holder.replace() }
        assertEquals(listOf(first), ended)
        assertEquals(0, holder.generation.value)

        failBuild = false
        val next = holder.current

        assertNotSame(first, next)
        assertEquals(listOf(first), ended)
        // The replacement finished here, so whatever follows the identity moves now.
        assertEquals(1, holder.generation.value)
        assertSame(next, holder.current)
        assertEquals(1, holder.generation.value)
    }

    @Test
    fun `the identity is ending from when a panic starts until it is replaced`() {
        val holder = holder()
        holder.current
        assertFalse(holder.ending.value)

        holder.beginEnding()
        assertTrue(holder.ending.value)

        holder.replace()
        assertFalse(holder.ending.value)
    }

    @Test
    fun `an ending that is called off keeps the identity`() {
        val holder = holder()
        val identity = holder.current
        holder.beginEnding()

        holder.cancelEnding()

        assertFalse(holder.ending.value)
        assertSame(identity, holder.current)
        assertEquals(0, holder.generation.value)
    }
}
