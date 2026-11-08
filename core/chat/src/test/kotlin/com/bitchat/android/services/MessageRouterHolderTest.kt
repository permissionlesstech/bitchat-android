package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageRouterHolderTest {

    private val builds = AtomicInteger()
    private val nostr: NostrTransport = mock()
    private val favorites: FavoritesPersistenceService = mock()
    private val built = java.util.Collections.synchronizedList(mutableListOf<MessageRouter>())
    private lateinit var stores: TestMeshStores
    private lateinit var nostrIdentity: NostrIdentityBridge

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("router-holder-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        stores = TestMeshStores(context, identityManagerProvider = { identityManager })
        nostrIdentity = NostrIdentityBridge(stores.favorites)
    }

    @After
    fun tearDown() {
        built.forEach { it.stopOutboxScheduler() }
    }

    private fun holder(disableScheduler: Boolean = true) = MessageRouterHolder(
        object : MessageRouter.Factory {
            override fun create(): MessageRouter {
                builds.incrementAndGet()
                return MessageRouter(
                    RuntimeEnvironment.getApplication(),
                    nostrIdentity,
                    favorites,
                    stores.contactDirectory,
                    stores.geohashAliases,
                    GeohashConversationRegistry(),
                    nostr,
                    disableScheduler,
                ).also(built::add)
            }
        },
    )

    private fun meshWithPeerId(peerID: String): MeshService = mock<MeshService>().also {
        whenever(it.myPeerID).thenReturn(peerID)
    }

    @Test
    fun `peek is null before the first attach and does not build`() {
        val holder = holder()

        assertNull(holder.peek())
        assertEquals(0, builds.get())
    }

    @Test
    fun `peek returns the attached router and does not re-point it`() {
        val holder = holder()
        val mesh = meshWithPeerId("1111222233334444")
        val router = holder.getOrAttach(mesh)
        clearInvocations(mesh, nostr)

        assertSame(router, holder.peek())
        verifyNoInteractions(mesh, nostr)
        assertEquals(1, builds.get())
    }

    @Test
    fun `the router is built once across attaches`() {
        val holder = holder()

        val first = holder.getOrAttach(meshWithPeerId("1111222233334444"))
        val second = holder.getOrAttach(meshWithPeerId("5555666677778888"))

        assertSame(first, second)
        assertEquals(1, builds.get())
    }

    @Test
    fun `each attach re-points the router at the given mesh and its peer id`() {
        val holder = holder()
        val firstMesh = meshWithPeerId("1111222233334444")
        val secondMesh = meshWithPeerId("5555666677778888")

        holder.getOrAttach(firstMesh)
        verify(nostr, atLeastOnce()).senderPeerID = "1111222233334444"

        val router = holder.getOrAttach(secondMesh)
        verify(nostr).senderPeerID = "5555666677778888"

        router.onPeersUpdated(listOf("aaaabbbbccccdddd"))
        verify(secondMesh).getPeerInfo("aaaabbbbccccdddd")
        verify(firstMesh, never()).getPeerInfo(any())
    }

    @Test
    fun `the outbox scheduler restarts on attach after a stop`() {
        val holder = holder(disableScheduler = false)
        val mesh = meshWithPeerId("1111222233334444")

        val router = holder.getOrAttach(mesh)
        assertTrue(router.isSchedulerRunning)

        holder.peek()?.stopOutboxScheduler()
        assertFalse(router.isSchedulerRunning)
        assertSame(router, holder.peek())
        assertFalse("peek does not restart the scheduler", router.isSchedulerRunning)

        holder.getOrAttach(mesh)
        assertTrue(router.isSchedulerRunning)
    }

    @Test
    fun `the favourites listener is registered once`() {
        val holder = holder()

        holder.getOrAttach(meshWithPeerId("1111222233334444"))
        holder.getOrAttach(meshWithPeerId("5555666677778888"))

        verify(favorites, times(1)).addListener(any())
    }

    @Test
    fun `a favourites store that refuses the listener does not stop the router`() {
        whenever(favorites.addListener(any())).thenThrow(IllegalStateException("not initialized"))
        val holder = holder()

        val router = holder.getOrAttach(meshWithPeerId("1111222233334444"))
        holder.getOrAttach(meshWithPeerId("5555666677778888"))

        assertSame(router, holder.peek())
        verify(favorites, times(1)).addListener(any()) // Not retried, as the static path did not retry.
    }

    @Test
    fun `concurrent first attaches build one router`() {
        val holder = holder()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val attaches = (1..threads).map { i ->
                val mesh = meshWithPeerId(i.toString().padStart(16, '0'))
                pool.submit<MessageRouter> {
                    start.await()
                    holder.getOrAttach(mesh)
                }
            }
            start.countDown()
            val seen = attaches.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(seen.all { it === seen.first() })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, builds.get())
        verify(favorites, times(1)).addListener(any())
    }

    @Test
    fun `a router is published only after it has its mesh`() {
        val holder = holder()
        var seenWhileRegistering: MessageRouter? = null
        var usableWhileRegistering = false
        whenever(favorites.addListener(any())).thenAnswer {
            seenWhileRegistering = holder.peek()
            // Reaches the mesh outside any catch, so an unset one throws here.
            built.single().sendFavoriteNotification("aaaabbbbccccdddd", false)
            usableWhileRegistering = true
            Unit
        }

        val router = holder.getOrAttach(meshWithPeerId("1111222233334444"))

        assertNull(seenWhileRegistering)
        assertTrue(usableWhileRegistering)
        assertSame(router, holder.peek())
    }
}
