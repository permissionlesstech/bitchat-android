package com.bitchat.android.identity

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class IdentityCacheBatchRobolectricTest {
    @Test
    fun `unchanged peer polls read each cache once without rewriting encrypted values`() {
        val counts = PreferenceCounts()
        val prefs = counts.preferences()
        val manager = SecureIdentityStateManager(prefs, testOnly = true)
        val peers = List(1_000) { index ->
            SecureIdentityStateManager.CachedPeerIdentity(
                "fixture-$index", (index + 1).toString(16).padStart(64, '0'),
                (index + 10_001).toString(16).padStart(64, '0'), "fixture-name-$index"
            )
        }
        manager.cachePeerIdentities(peers)
        assertEquals(4L, counts.writes)
        assertEquals(4_000L, counts.writtenEntries)
        counts.reset()
        manager.cachePeerIdentities(peers)
        assertEquals(4_000L, counts.readEntries)
        assertEquals(0L, counts.writes)

        manager.cachePeerIdentities(listOf(peers[0].copy(nickname = "changed-fixture")))
        assertEquals(1L, counts.writes)
        val restored = SecureIdentityStateManager(prefs, testOnly = true)
        assertEquals("changed-fixture", restored.getCachedFingerprintNickname(peers[0].fingerprint))
        assertEquals(peers[1].nickname, restored.getCachedFingerprintNickname(peers[1].fingerprint))
        assertEquals(peers[0].noiseKeyHex, restored.getCachedNoiseKey(peers[0].peerID))
        assertEquals(peers[0].fingerprint, restored.getCachedPeerFingerprint(peers[0].peerID))
    }

    @Test
    fun `individual cache setters ignore identical values`() {
        val counts = PreferenceCounts()
        val manager = SecureIdentityStateManager(counts.preferences(), testOnly = true)
        val key = "1".padStart(64, '0')
        repeat(2) {
            manager.cachePeerFingerprint("fixture", key)
            manager.cachePeerNoiseKey("fixture", key)
            manager.cacheNoiseFingerprint(key, key)
            manager.cacheFingerprintNickname(key, "fixture-name")
        }
        assertEquals(4L, counts.writes)
    }

    @Test
    fun `queued pre-panic snapshot cannot restore wiped identities and new snapshots still persist`() {
        val counts = PreferenceCounts()
        val prefs = counts.preferences()
        val pollManager = SecureIdentityStateManager(prefs, testOnly = true)
        val wipeManager = SecureIdentityStateManager(prefs, testOnly = true)
        val peer = SecureIdentityStateManager.CachedPeerIdentity(
            "fixture", "1".padStart(64, '0'), "2".padStart(64, '0'), "fixture-name"
        )
        val generationBeforeDispatch = pollManager.cacheGeneration()
        pollManager.cachePeerIdentities(listOf(peer), generationBeforeDispatch)
        wipeManager.clearIdentityData()
        counts.reset()
        // Deterministically model the I/O poll resuming after the main-thread panic.
        pollManager.cachePeerIdentities(listOf(peer), generationBeforeDispatch)
        assertEquals(0L, counts.writes)
        assertEquals(null, pollManager.getCachedPeerFingerprint(peer.peerID))
        assertEquals(null, pollManager.getCachedNoiseKey(peer.peerID))
        assertEquals(null, pollManager.getCachedNoiseFingerprint(peer.noiseKeyHex!!))
        assertEquals(null, pollManager.getCachedFingerprintNickname(peer.fingerprint))
        pollManager.cachePeerIdentities(listOf(peer))
        assertEquals(4L, counts.writes)
    }

    private class PreferenceCounts {
        private val sets = mutableMapOf<String, Set<String>>()
        var readEntries = 0L
        var writtenEntries = 0L
        var writes = 0L
        fun reset() { readEntries = 0; writtenEntries = 0; writes = 0 }
        fun preferences(): SharedPreferences {
            val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putStringSet" -> {
                        @Suppress("UNCHECKED_CAST")
                        val values = args!![1] as Set<String>
                        sets[args[0] as String] = values.toSet()
                        writtenEntries += values.size
                        writes++
                        proxy
                    }
                    "clear" -> { sets.clear(); proxy }
                    "apply" -> null
                    "commit" -> true
                    else -> proxy
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getStringSet" -> {
                        val value = sets[args!![0] as String] ?: emptySet<String>()
                        readEntries += value.size
                        value
                    }
                    "edit" -> editor
                    "contains" -> sets.containsKey(args!![0])
                    "getAll" -> sets.toMap()
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
