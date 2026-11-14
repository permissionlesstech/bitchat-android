package com.bitchat.android.mesh

import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityComponentBuilder
import com.bitchat.android.identity.scope.IdentityEnder
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.panic.testPanicMarker
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
    private var currentStore = store
    private var reads = 0
    private val provider = Provider<AppStateStore> {
        reads++
        currentStore
    }
    private val holder = IdentityHolder(
        Provider {
            object : IdentityComponentBuilder {
                override fun build(): IdentityComponent = object : IdentityComponent {}
            }
        },
        IdentityEnder { emptyList() },
        testPanicMarker(),
    )

    // Unconfined, so collection runs inside `start()` and the test needs no waiting.
    private val manager = PowerManager(application, holder, provider, Dispatchers.Unconfined)

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
    fun `power manager reads the new identity's direct peers after replacement`() {
        store.setTransportDirectPeers("ble", listOf("0011223344556677"))
        manager.start()
        assertTrue(manager.profile.value.hasDirectPeers)

        val newStore = TestMeshStores(application).appStateStore
        currentStore = newStore
        holder.replace { true }

        assertFalse("the new identity has no direct peers yet", manager.profile.value.hasDirectPeers)

        newStore.setTransportDirectPeers("ble", listOf("8899aabbccddeeff"))
        assertTrue(manager.profile.value.hasDirectPeers)

        newStore.clearTransportDirectPeers("ble")
        assertFalse(manager.profile.value.hasDirectPeers)
    }

    @Test
    fun `the old identity's direct peers stop counting after replacement`() {
        manager.start()

        currentStore = TestMeshStores(application).appStateStore
        holder.replace { true }
        store.setTransportDirectPeers("ble", listOf("0011223344556677"))

        assertFalse(manager.profile.value.hasDirectPeers)
    }

    @Test
    fun `starting after shutdown reads nothing`() {
        manager.shutdown()

        manager.start()

        assertEquals(0, reads)
    }
}
