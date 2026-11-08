package com.bitchat.android.identity.scope

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityHolderTest {

    private val builds = AtomicInteger()

    private val builder = Provider<IdentityComponentBuilder> {
        object : IdentityComponentBuilder {
            override fun build(): IdentityComponent {
                builds.incrementAndGet()
                return object : IdentityComponent {}
            }
        }
    }

    private fun holder() = IdentityHolder(builder)

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
}
