package com.bitchat.android.mesh

import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.TestMeshStores
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The power manager outlives an identity, so it must not read one until it is started. */
@RunWith(RobolectricTestRunner::class)
class PowerManagerIdentityTest {
    private val application = RuntimeEnvironment.getApplication()
    private val store = TestMeshStores(application).appStateStore
    private var reads = 0
    private val provider = Provider<AppStateStore> {
        reads++
        store
    }

    // Unconfined, so collection runs inside `start()` and the test needs no waiting.
    private val manager = PowerManager(application, provider, Dispatchers.Unconfined)

    @After
    fun tearDown() = manager.shutdown()

    @Test
    fun `building the power manager does not touch the identity`() {
        assertEquals(0, reads)
    }

    @Test
    fun `starting reads the store once, however often it is called`() {
        manager.start()
        manager.start()

        assertEquals(1, reads)
    }

    @Test
    fun `a started manager follows the direct peers`() {
        store.setTransportDirectPeers("ble", listOf("0011223344556677"))
        assertFalse(manager.profile.value.hasDirectPeers)

        manager.start()
        assertTrue(manager.profile.value.hasDirectPeers)

        store.clearTransportDirectPeers("ble")
        assertFalse(manager.profile.value.hasDirectPeers)
    }

    @Test
    fun `starting after shutdown reads nothing`() {
        manager.shutdown()

        manager.start()

        assertEquals(0, reads)
    }
}
