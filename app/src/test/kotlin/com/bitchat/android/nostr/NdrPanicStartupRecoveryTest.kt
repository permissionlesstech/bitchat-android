package com.bitchat.android.nostr

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrPanicStartupRecoveryTest {
    @After
    fun restoreNetworkGate() {
        NdrPanicStartupRecovery.recoverBeforeNetwork(
            markerStore = InMemoryMarkerStore(retryRequired = false),
            panicStorageQuarantine = InMemoryQuarantine(pending = false),
            clearIdentity = { true },
            clearFavorites = { true }
        )
    }

    @Test
    fun startupRetryFinishesNativeAndHostWipeBeforeAllowingNetwork() {
        val markers = InMemoryMarkerStore(retryRequired = true)
        val quarantine = InMemoryQuarantine(pending = false)
        var identityCleared = false
        var favoritesCleared = false

        val recovered = NdrPanicStartupRecovery.recoverBeforeNetwork(
            markerStore = markers,
            panicStorageQuarantine = quarantine,
            clearIdentity = {
                assertTrue(quarantine.nativeStateWiped)
                identityCleared = true
                true
            },
            clearFavorites = {
                assertTrue(identityCleared)
                favoritesCleared = true
                true
            }
        )

        assertTrue(recovered)
        assertTrue(identityCleared)
        assertTrue(favoritesCleared)
        assertFalse(markers.retryRequired)
        assertFalse(quarantine.pending)
        assertTrue(NdrPanicStartupRecovery.isNetworkStartupAllowed())
    }

    @Test
    fun failedHostWipeKeepsStartupInertAndRetryMarkerDurable() {
        val markers = InMemoryMarkerStore(retryRequired = true)
        val quarantine = InMemoryQuarantine(pending = false)

        val recovered = NdrPanicStartupRecovery.recoverBeforeNetwork(
            markerStore = markers,
            panicStorageQuarantine = quarantine,
            clearIdentity = { true },
            clearFavorites = { false }
        )

        assertFalse(recovered)
        assertTrue(markers.retryRequired)
        assertTrue(quarantine.pending)
        assertFalse(NdrPanicStartupRecovery.isNetworkStartupAllowed())
    }

    @Test
    fun failedIdentityWipeKeepsStartupInertAndBothRetrySignalsDurable() {
        val markers = InMemoryMarkerStore(retryRequired = true)
        val quarantine = InMemoryQuarantine(pending = false)
        var favoritesClearCalled = false

        val recovered = NdrPanicStartupRecovery.recoverBeforeNetwork(
            markerStore = markers,
            panicStorageQuarantine = quarantine,
            clearIdentity = { false },
            clearFavorites = {
                favoritesClearCalled = true
                true
            }
        )

        assertFalse(recovered)
        assertFalse(favoritesClearCalled)
        assertTrue(markers.retryRequired)
        assertTrue(quarantine.pending)
        assertFalse(NdrPanicStartupRecovery.isNetworkStartupAllowed())
    }

    @Test
    fun quarantineResidueAloneTriggersRecoveryBeforeNetworkStartup() {
        val markers = InMemoryMarkerStore(retryRequired = false)
        val quarantine = InMemoryQuarantine(pending = true)
        var identityCleared = false

        val recovered = NdrPanicStartupRecovery.recoverBeforeNetwork(
            markerStore = markers,
            panicStorageQuarantine = quarantine,
            clearIdentity = {
                identityCleared = true
                true
            },
            clearFavorites = { true }
        )

        assertTrue(recovered)
        assertTrue(identityCleared)
        assertTrue(quarantine.nativeStateWiped)
        assertFalse(quarantine.pending)
        assertTrue(NdrPanicStartupRecovery.isNetworkStartupAllowed())
    }

    private class InMemoryMarkerStore(
        var retryRequired: Boolean
    ) : NdrEstablishedSessionMarkerStore {
        override fun contains(accountPubkeyHex: String): Boolean = false
        override fun mark(accountPubkeyHex: String) = Unit
        override fun clearEstablishedSessions() = Unit
        override fun isPanicWipeRequired(): Boolean = retryRequired
        override fun markPanicWipeRequired() {
            retryRequired = true
        }
        override fun clearPanicWipeRequired() {
            retryRequired = false
        }
    }

    private class InMemoryQuarantine(
        var pending: Boolean
    ) : NdrPanicStorageQuarantine {
        var nativeStateWiped = false

        override fun isPending(): Boolean = pending

        override fun begin() {
            pending = true
        }

        override fun wipeNativeState() {
            check(pending)
            nativeStateWiped = true
        }

        override fun clear() {
            check(nativeStateWiped)
            pending = false
        }
    }
}
