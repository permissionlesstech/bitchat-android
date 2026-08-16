package com.bitchat.android.favorites

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ContactIdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private lateinit var preferences: FaultInjectingSharedPreferences
    private val noiseA = ByteArray(32) { 1 }
    private val noiseB = ByteArray(32) { 2 }
    private val oldPeer = "11".repeat(32)
    private val newPeer = "22".repeat(32)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = FaultInjectingSharedPreferences(
            context.getSharedPreferences(
                "favorites-ndr-rebind-${System.nanoTime()}",
                Context.MODE_PRIVATE
            )
        )
        service = newService()
    }

    private fun newService(): FavoritesPersistenceService {
        service = FavoritesPersistenceService(
            stateManager = SecureIdentityStateManager(preferences, testOnly = true),
            testOnly = true
        )
        return service
    }

    @Test
    fun persistenceFailureBeforeRebindDoesNotRetireOrReportSuccess() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        var retireCalls = 0
        service.setNdrPeerRetirementGuard {
            retireCalls += 1
            true
        }

        preferences.failNextWrite = true
        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))

        assertEquals(0, retireCalls)
        val restarted = newService()
        assertEquals(oldPeer, restarted.findNdrSessionPubkeyHex(noiseA))
        assertTrue(restarted.isNdrRequired(noiseA))
    }

    @Test
    fun failedTargetPersistenceKeepsJournalAndExposesOnlyTargetRoute() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        var retireCalls = 0
        service.setNdrPeerRetirementGuard {
            retireCalls += 1
            true
        }

        preferences.successfulWritesBeforeFailure = 1
        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))

        assertEquals(1, retireCalls)
        assertTrue(service.isNdrRebindBlocked(noiseA))
        assertTrue(service.isNdrRequired(noiseA))
        assertEquals(newPeer, service.findNdrSessionPubkeyHex(noiseA))
        assertEquals(noiseA.toList(), service.findNoiseKey(newPeer)?.toList())
        assertFalse(service.isCurrentNdrPeerAuthorized(oldPeer))
        assertNull(service.getStoredFavoriteForNdrRoute(noiseA))
    }

    @Test
    fun restartCompletesJournalBeforeUnblockingTarget() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        var failedRetireCalls = 0
        service.setNdrPeerRetirementGuard {
            failedRetireCalls += 1
            false
        }

        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))
        assertEquals(1, failedRetireCalls)
        assertEquals(newPeer, service.findNdrSessionPubkeyHex(noiseA))
        assertTrue(service.isNdrRequired(noiseA))
        assertTrue(service.isNdrRebindBlocked(noiseA))

        val retiredAfterRestart = mutableListOf<String>()
        val restarted = newService()
        restarted.setNdrPeerRetirementGuard {
            retiredAfterRestart += it
            true
        }

        assertEquals(listOf(oldPeer), retiredAfterRestart)
        assertEquals(newPeer, restarted.findNdrSessionPubkeyHex(noiseA))
        assertTrue(restarted.isNdrRequired(noiseA))
        assertFalse(restarted.isNdrRebindBlocked(noiseA))

        val verifiedRestart = newService()
        assertEquals(newPeer, verifiedRestart.findNdrSessionPubkeyHex(noiseA))
        assertTrue(verifiedRestart.isNdrRequired(noiseA))
        assertFalse(verifiedRestart.isNdrRebindBlocked(noiseA))
    }

    @Test
    fun failedJournalRemovalRetriesIdempotentRetirementAfterRestart() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        val retired = mutableListOf<String>()
        service.setNdrPeerRetirementGuard {
            retired += it
            true
        }
        preferences.failNextRemovalOf =
            FavoritesPersistenceService.NDR_REBIND_JOURNAL_KEY

        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))
        assertEquals(listOf(oldPeer), retired)
        assertTrue(service.isNdrRebindBlocked(noiseA))
        assertTrue(service.isNdrRequired(noiseA))
        assertEquals(
            newPeer,
            service.getStoredFavoriteForNdrRoute(noiseA)
                ?.peerNdrSessionPubkeyHex
        )
        assertTrue(service.isCurrentNdrPeerAuthorized(newPeer))

        val restarted = newService()
        restarted.setNdrPeerRetirementGuard {
            retired += it
            true
        }

        assertEquals(listOf(oldPeer, oldPeer), retired)
        assertEquals(newPeer, restarted.findNdrSessionPubkeyHex(noiseA))
        assertTrue(restarted.isNdrRequired(noiseA))
        assertFalse(restarted.isNdrRebindBlocked(noiseA))
    }

    @Test
    fun initialIdentityPersistenceFailureDoesNotMutateMemory() {
        preferences.failNextWrite = true

        assertFalse(service.updateNostrPublicKey(noiseA, oldPeer))

        assertNull(service.getFavoriteStatus(noiseA))
        assertNull(newService().getFavoriteStatus(noiseA))
    }

    @Test
    fun existingNativeSessionBackfillRetriesAfterPinMarkerWriteFailure() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        preferences.failNextWrite = true

        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        assertFalse(service.isNdrProtectionStateReadable())
        assertTrue(service.isNdrRebindBlocked(noiseA))

        val restarted = newService()
        assertFalse(restarted.isNdrRequired(noiseA))
        assertTrue(restarted.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        assertTrue(restarted.isNdrRequired(noiseA))
        assertFalse(restarted.isNdrRebindBlocked(noiseA))
        assertTrue(newService().isNdrRequired(noiseA))
    }

    @Test
    fun pinOnlyJournalRecoversAfterCrashBeforeFavoriteCommit() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        preferences.successfulWritesBeforeFailure = 1

        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        assertTrue(service.isNdrRequired(noiseA))
        assertTrue(service.isNdrRebindBlocked(noiseA))
        assertNull(service.getStoredFavoriteForNdrRoute(noiseA))

        val restarted = newService()
        restarted.setNdrPeerRetirementGuard {
            error("pin-only recovery must not retire")
        }

        assertTrue(restarted.isNdrRequired(noiseA))
        assertFalse(restarted.isNdrRebindBlocked(noiseA))
        assertEquals(
            oldPeer,
            restarted.getStoredFavoriteForNdrRoute(noiseA)
                ?.peerNdrSessionPubkeyHex
        )
    }

    @Test
    fun corruptJournalFailsClosed() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(
            preferences.edit()
                .putString(
                    FavoritesPersistenceService.NDR_REBIND_JOURNAL_KEY,
                    """{"version":1,"noiseKeyHex":"broken"}"""
                )
                .commit()
        )

        val restarted = newService()

        assertTrue(restarted.isNdrRebindBlocked(noiseA))
        assertFalse(restarted.updateNdrSessionPubkeyHex(noiseA, newPeer))
        assertEquals(oldPeer, restarted.findNdrSessionPubkeyHex(noiseA))
        assertNull(restarted.getStoredFavoriteForNdrRoute(noiseA))
    }

    @Test
    fun failedRetirementQuarantinesTargetWithoutHoldingFavoritesLock() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
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
            newPeer,
            service.findNdrSessionPubkeyHex(noiseA)
        )
        assertTrue(service.isNdrRequired(noiseA))
        assertTrue(service.isNdrRebindBlocked(noiseA))
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
    fun equivalentNpubAndHexAssignmentDoesNotWedgePinnedContact() {
        val oldNpub = requireNotNull(ContactIdentityResolver.npubFromHex(oldPeer))
        assertTrue(service.updateNostrPublicKey(noiseA, oldNpub))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        var retireCalls = 0
        service.setNdrPeerRetirementGuard {
            retireCalls += 1
            true
        }

        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer.uppercase()))

        assertEquals(0, retireCalls)
        assertTrue(service.isNdrRequired(noiseA))
        assertFalse(service.isNdrRebindBlocked(noiseA))
        assertEquals(oldPeer, service.findNdrSessionPubkeyHex(noiseA))
    }

    @Test
    fun preNdrNostrRebindDoesNotPinOrRetireLegacyContact() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        var retireCalls = 0
        service.setNdrPeerRetirementGuard {
            retireCalls += 1
            true
        }

        assertTrue(service.updateNostrPublicKey(noiseA, newPeer))

        assertEquals(0, retireCalls)
        assertFalse(service.isNdrRequired(noiseA))
        assertFalse(service.isNdrRebindBlocked(noiseA))
        assertEquals(newPeer, service.findNdrSessionPubkeyHex(noiseA))
    }

    @Test
    fun sharedPinnedIdentityIsRetiredOnlyAfterItsLastFavoriteMoves() {
        insertRelationship(noiseA, oldPeer, pinned = true)
        insertRelationship(noiseB, oldPeer, pinned = true)
        val retired = mutableListOf<String>()
        service.setNdrPeerRetirementGuard {
            retired += it
            true
        }

        assertTrue(service.updateNostrPublicKey(noiseA, newPeer))
        assertTrue(retired.isEmpty())
        assertTrue(service.isNdrRequired(noiseA))

        val finalPeer = "33".repeat(32)
        assertTrue(service.updateNostrPublicKey(noiseB, finalPeer))
        assertEquals(listOf(oldPeer), retired)
        assertEquals(newPeer, service.findNdrSessionPubkeyHex(noiseA))
        assertEquals(finalPeer, service.findNdrSessionPubkeyHex(noiseB))
        assertTrue(service.isNdrRequired(noiseB))
    }

    @Test
    fun existingExplicitNdrBindingMigratesToDurablePin() {
        val noiseHex = ContactIdentityResolver.noiseKeyHex(noiseA)
        val oldNpub = requireNotNull(ContactIdentityResolver.npubFromHex(oldPeer))
        val legacyJson = """
            {
              "$noiseHex": {
                "peerNoisePublicKeyHex": "$noiseHex",
                "peerNostrPublicKey": "$oldNpub",
                "peerNdrSessionPubkeyHex": "$oldPeer",
                "peerNickname": "legacy",
                "isFavorite": true,
                "theyFavoritedUs": true,
                "favoritedAt": 1,
                "lastUpdated": 1
              }
            }
        """.trimIndent()
        assertTrue(
            preferences.edit()
                .putString(FavoritesPersistenceService.FAVORITES_KEY, legacyJson)
                .commit()
        )

        val migrated = newService()

        assertTrue(migrated.isNdrRequired(noiseA))
        assertTrue(
            requireNotNull(
                preferences.getString(FavoritesPersistenceService.FAVORITES_KEY, null)
            ).contains("\"ndrRequired\":true")
        )
        assertTrue(newService().isNdrRequired(noiseA))
    }

    @Test
    fun oldPeerIsRetiredOnlyAfterJournalWhileStoredBindingIsStillOld() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        var storedBindingWasOld = false
        var journalWasPresent = false
        service.setNdrPeerRetirementGuard {
            val stored = requireNotNull(
                preferences.getString(FavoritesPersistenceService.FAVORITES_KEY, null)
            )
            storedBindingWasOld =
                stored.contains("\"peerNdrSessionPubkeyHex\":\"$oldPeer\"") &&
                    !stored.contains("\"peerNdrSessionPubkeyHex\":\"$newPeer\"")
            journalWasPresent =
                preferences.contains(FavoritesPersistenceService.NDR_REBIND_JOURNAL_KEY)
            true
        }

        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, newPeer))

        assertTrue(journalWasPresent)
        assertTrue(storedBindingWasOld)
        assertEquals(newPeer, service.findNdrSessionPubkeyHex(noiseA))
    }

    @Test
    fun journalCannotRetirePeerItDoesNotOwn() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        val unrelated = "44".repeat(32)
        val noiseHex = ContactIdentityResolver.noiseKeyHex(noiseA)
        val invalidJournal = """
            {
              "version": 1,
              "noiseKeyHex": "$noiseHex",
              "oldPeerPubkeyHex": "$unrelated",
              "expectedNostrPubkeyHex": "$oldPeer",
              "expectedNdrSessionPubkeyHex": "$oldPeer",
              "expectedNdrRequired": true,
              "targetNostrPubkeyHex": "$oldPeer",
              "targetNdrSessionPubkeyHex": "$newPeer",
              "targetNdrRequired": true,
              "retireOldPeer": true
            }
        """.trimIndent()
        assertTrue(
            preferences.edit()
                .putString(
                    FavoritesPersistenceService.NDR_REBIND_JOURNAL_KEY,
                    invalidJournal
                )
                .commit()
        )
        var retireCalls = 0
        val restarted = newService()

        restarted.setNdrPeerRetirementGuard {
            retireCalls += 1
            true
        }

        assertEquals(0, retireCalls)
        assertTrue(restarted.isNdrRebindBlocked(noiseA))
        assertEquals(oldPeer, restarted.findNdrSessionPubkeyHex(noiseA))
    }

    @Test
    fun targetIsRevalidatedAfterRetirementBeforeJournalClear() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        service.setNdrPeerRetirementGuard {
            insertRelationship(noiseA, "55".repeat(32), pinned = true)
            true
        }

        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))

        assertTrue(service.isNdrRebindBlocked(noiseA))
        assertTrue(
            preferences.contains(FavoritesPersistenceService.NDR_REBIND_JOURNAL_KEY)
        )
    }

    @Test
    fun stalePeerIsRejectedDuringRebindButSharedCurrentPeerRemainsAuthorized() {
        insertRelationship(noiseA, oldPeer, pinned = true)
        assertTrue(service.isCurrentNdrPeerAuthorized(oldPeer))
        service.setNdrPeerRetirementGuard { false }

        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))

        assertFalse(service.isCurrentNdrPeerAuthorized(oldPeer))
        assertFalse(service.isCurrentNdrPeerAuthorized(newPeer))

        insertRelationship(noiseB, oldPeer, pinned = true)
        assertTrue(service.isCurrentNdrPeerAuthorized(oldPeer))
    }

    @Test
    fun pendingTargetIsReservedAgainstEveryOtherFavorite() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        service.setNdrPeerRetirementGuard { false }
        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))

        assertFalse(service.updateNostrPublicKey(noiseB, newPeer))
        assertNull(service.getFavoriteStatus(noiseB))
        assertEquals(noiseA.toList(), service.findNoiseKey(newPeer)?.toList())
    }

    @Test
    fun legacyInboundIsRejectedForPinJournalAndUnreadableProtectionState() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.isLegacyNostrInboundAllowed(oldPeer))

        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        assertFalse(service.isLegacyNostrInboundAllowed(oldPeer))

        service.setNdrPeerRetirementGuard { false }
        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))
        assertFalse(service.isLegacyNostrInboundAllowed(oldPeer))
        assertFalse(service.isLegacyNostrInboundAllowed(newPeer))

        assertTrue(
            preferences.edit()
                .remove(FavoritesPersistenceService.NDR_REBIND_JOURNAL_KEY)
                .putString(FavoritesPersistenceService.FAVORITES_KEY, "{broken")
                .commit()
        )
        val unreadable = newService()
        assertFalse(unreadable.isNdrProtectionStateReadable())
        assertFalse(unreadable.isLegacyNostrInboundAllowed("66".repeat(32)))
    }

    @Test
    fun successfulNativeResetAtomicallyClearsPinsAndJournal() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))
        assertTrue(service.updateNdrSessionPubkeyHex(noiseA, oldPeer))
        service.setNdrPeerRetirementGuard { false }
        assertFalse(service.updateNdrSessionPubkeyHex(noiseA, newPeer))

        assertTrue(service.clearAllFavoritesAfterNdrReset())

        assertNull(service.getFavoriteStatus(noiseA))
        assertFalse(preferences.contains(FavoritesPersistenceService.FAVORITES_KEY))
        assertFalse(preferences.contains(FavoritesPersistenceService.PEERID_INDEX_KEY))
        assertFalse(
            preferences.contains(FavoritesPersistenceService.NDR_REBIND_JOURNAL_KEY)
        )
    }

    @Test
    fun newIdentityCannotBeBoundToTwoFavorites() {
        assertTrue(service.updateNostrPublicKey(noiseA, oldPeer))

        assertFalse(service.updateNostrPublicKey(noiseB, oldPeer))
        assertEquals(null, service.getFavoriteStatus(noiseB))
    }

    @Suppress("UNCHECKED_CAST")
    private fun insertRelationship(
        noiseKey: ByteArray,
        peerPubkeyHex: String,
        pinned: Boolean
    ) {
        val field = FavoritesPersistenceService::class.java.getDeclaredField("favorites")
        field.isAccessible = true
        val favorites = field.get(service) as MutableMap<String, FavoriteRelationship>
        favorites[ContactIdentityResolver.noiseKeyHex(noiseKey)] = FavoriteRelationship(
            peerNoisePublicKey = noiseKey,
            peerNostrPublicKey =
                requireNotNull(ContactIdentityResolver.npubFromHex(peerPubkeyHex)),
            peerNdrSessionPubkeyHex = peerPubkeyHex.takeIf { pinned },
            ndrRequired = pinned,
            peerNickname = "test",
            isFavorite = true,
            theyFavoritedUs = true,
            favoritedAt = Date(1),
            lastUpdated = Date(1)
        )
    }

    private class FaultInjectingSharedPreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences by delegate {
        var failNextWrite: Boolean = false
        var successfulWritesBeforeFailure: Int? = null
        var failNextRemovalOf: String? = null

        override fun edit(): SharedPreferences.Editor =
            FaultInjectingEditor(delegate.edit())

        private inner class FaultInjectingEditor(
            private val delegateEditor: SharedPreferences.Editor
        ) : SharedPreferences.Editor by delegateEditor {
            private val removedKeys = mutableSetOf<String>()

            override fun putString(
                key: String?,
                value: String?
            ): SharedPreferences.Editor {
                delegateEditor.putString(key, value)
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                delegateEditor.putStringSet(key, values)
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                delegateEditor.putInt(key, value)
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                delegateEditor.putLong(key, value)
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                delegateEditor.putFloat(key, value)
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                delegateEditor.putBoolean(key, value)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                delegateEditor.remove(key)
                key?.let(removedKeys::add)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                delegateEditor.clear()
                return this
            }

            override fun commit(): Boolean {
                if (shouldFailWrite()) return false
                return delegateEditor.commit()
            }

            override fun apply() {
                if (shouldFailWrite()) return
                delegateEditor.apply()
            }

            private fun shouldFailWrite(): Boolean {
                val removalKey = failNextRemovalOf
                if (removalKey != null && removalKey in removedKeys) {
                    failNextRemovalOf = null
                    return true
                }
                if (failNextWrite) {
                    failNextWrite = false
                    return true
                }
                val writesRemaining = successfulWritesBeforeFailure ?: return false
                if (writesRemaining == 0) {
                    successfulWritesBeforeFailure = null
                    return true
                }
                successfulWritesBeforeFailure = writesRemaining - 1
                return false
            }
        }
    }
}
