package com.bitchat.watch.mesh

import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Creating the watch mesh generates its identity keys, so it must happen only when
 * [WearMeshHolder.getOrCreate] is first called (after permissions and Bluetooth are confirmed),
 * never when the holder is injected.
 */
class WearMeshHolderTest {

    /** Hands out a fresh mock per call and counts the calls. */
    private class CountingProvider : Provider<WearMeshService> {
        var calls = 0
            private set

        override fun get(): WearMeshService {
            calls++
            return mock()
        }
    }

    @Test
    fun peek_beforeGetOrCreate_isNull() {
        val provider = CountingProvider()
        val holder = WearMeshHolder(provider)

        assertNull(holder.peek())
        assertEquals(0, provider.calls)
    }

    @Test
    fun getOrCreate_twice_returnsOneInstance() {
        val provider = CountingProvider()
        val holder = WearMeshHolder(provider)

        val first = holder.getOrCreate()
        val second = holder.getOrCreate()

        assertSame(first, second)
        assertEquals(1, provider.calls)
    }

    @Test
    fun peek_afterGetOrCreate_returnsIt() {
        val holder = WearMeshHolder(CountingProvider())

        val created = holder.getOrCreate()

        assertSame(created, holder.peek())
    }
}
