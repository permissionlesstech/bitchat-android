package com.bitchat.android.favorites

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ContactIdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class FavoritesNdrRebindTest {
    private lateinit var service: FavoritesPersistenceService
    private val noiseA = ByteArray(32) { 1 }
    private val noiseB = ByteArray(32) { 2 }
    private val oldPeer = "11".repeat(32)
    private val newPeer = "22".repeat(32)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(
            "favorites-ndr-rebind-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        service = FavoritesPersistenceService(
            stateManager = SecureIdentityStateManager(preferences, testOnly = true),
            testOnly = true
        )
    }

    @Test
    fun failedRetirementRollsBackBindingWithoutHoldingFavoritesLock() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        var favoritesLockWasAvailable = false
        service.setNdrPeerRetirementGuard {
            val completed = CountDownLatch(1)
            thread {
                service.getFavoriteStatus(noiseA)
                completed.countDown()
            }
            favoritesLockWasAvailable = completed.await(1, TimeUnit.SECONDS)
            false
        }

        assertFalse(service.updateNostrPublicKey(noiseA, newPeer))

        assertTrue(favoritesLockWasAvailable)
        assertEquals(
            oldPeer,
            service.findNdrSessionPubkeyHex(noiseA)
        )
    }

    @Test
    fun sameIdentityIsANoOpAndDoesNotRetire() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        var retireCalls = 0
        service.setNdrPeerRetirementGuard {
            retireCalls += 1
            false
        }

        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))

        assertEquals(0, retireCalls)
        assertEquals(oldPeer, service.findNdrSessionPubkeyHex(noiseA))
    }

    @Test
    fun sharedLegacyIdentityIsRetiredOnlyAfterItsLastFavoriteMoves() {
        insertLegacyRelationship(noiseA, oldPeer)
        insertLegacyRelationship(noiseB, oldPeer)
        val retired = mutableListOf<String>()
        service.setNdrPeerRetirementGuard {
            retired += it
            true
        }

        assertTrue(service.updateNostrPublicKey(noiseA, newPeer))
        assertTrue(retired.isEmpty())

        val finalPeer = "33".repeat(32)
        assertTrue(service.updateNostrPublicKey(noiseB, finalPeer))
        assertEquals(listOf(oldPeer), retired)
        assertEquals(newPeer, service.findNdrSessionPubkeyHex(noiseA))
        assertEquals(finalPeer, service.findNdrSessionPubkeyHex(noiseB))
    }

    @Test
    fun newIdentityCannotBeBoundToTwoFavorites() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))

        assertFalse(service.updateNostrPublicKey(noiseB, oldPeer))
        assertEquals(null, service.getFavoriteStatus(noiseB))
    }

    @Suppress("UNCHECKED_CAST")
    private fun insertLegacyRelationship(noiseKey: ByteArray, peerPubkeyHex: String) {
        val field = FavoritesPersistenceService::class.java.getDeclaredField("favorites")
        field.isAccessible = true
        val favorites = field.get(service) as MutableMap<String, FavoriteRelationship>
        favorites[ContactIdentityResolver.noiseKeyHex(noiseKey)] = FavoriteRelationship(
            peerNoisePublicKey = noiseKey,
            peerNostrPublicKey =
                requireNotNull(ContactIdentityResolver.npubFromHex(peerPubkeyHex)),
            peerNickname = "legacy",
            isFavorite = true,
            theyFavoritedUs = true,
            favoritedAt = Date(1),
            lastUpdated = Date(1)
        )
    }
}
