package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NostrBackgroundBlocklistTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppStateStore.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppStateStore.clear()
        DataManager(app).clearAllData()
    }

    @Test
    @Config(sdk = [29])
    fun `DataManager clearAllData lifts geohash sender block through public event handling on API 29`() = runBlocking {
        val externalDataManager = DataManager(app)
        externalDataManager.clearAllData()

        val blockedSender = NostrIdentity.generate()
        val unblockedSender = NostrIdentity.generate()
        externalDataManager.addGeohashBlockedUser(blockedSender.publicKeyHex)

        val participantStore = GeohashParticipantStore()
        val processor = NostrBackgroundEventProcessor(app, scope, participantStore)
        val geohash = "u4pruy"
        val nowSec = (System.currentTimeMillis() / 1_000L).toInt()

        // Send a presence event from the blocked sender followed by a sentinel presence event from
        // an unblocked sender. Because processor serializes on a single-threaded IO dispatcher,
        // once the sentinel lands in participantStore the earlier blocked event was already dropped.
        val blockedBeforeWipe = NostrEvent(
            pubkey = blockedSender.publicKeyHex,
            createdAt = nowSec,
            kind = NostrKind.GEOHASH_PRESENCE,
            tags = listOf(listOf("g", geohash)),
            content = ""
        ).sign(blockedSender.privateKeyHex)
        val sentinel = NostrEvent(
            pubkey = unblockedSender.publicKeyHex,
            createdAt = nowSec,
            kind = NostrKind.GEOHASH_PRESENCE,
            tags = listOf(listOf("g", geohash)),
            content = ""
        ).sign(unblockedSender.privateKeyHex)

        processor.onGeohashMessage(blockedBeforeWipe, geohash)
        processor.onGeohashMessage(sentinel, geohash)

        withTimeout(5_000) {
            while (unblockedSender.publicKeyHex !in participantStore.snapshots.value.participants[geohash].orEmpty()) {
                kotlinx.coroutines.yield()
            }
        }
        val beforeParticipants = participantStore.snapshots.value.participants[geohash].orEmpty()
        assertFalse(
            "blocked sender event must be dropped prior to wipe",
            blockedSender.publicKeyHex in beforeParticipants
        )

        // Panic wipe through DataManager.clearAllData() calls SharedPreferences.Editor.clear(),
        // which omits OnSharedPreferenceChangeListener callbacks on Android API 26-29.
        externalDataManager.clearAllData()

        val afterWipe = NostrEvent(
            pubkey = blockedSender.publicKeyHex,
            createdAt = nowSec + 1,
            kind = NostrKind.GEOHASH_PRESENCE,
            tags = listOf(listOf("g", geohash)),
            content = ""
        ).sign(blockedSender.privateKeyHex)
        processor.onGeohashMessage(afterWipe, geohash)

        withTimeout(5_000) {
            while (blockedSender.publicKeyHex !in participantStore.snapshots.value.participants[geohash].orEmpty()) {
                kotlinx.coroutines.yield()
            }
        }
        val afterParticipants = participantStore.snapshots.value.participants[geohash].orEmpty()
        assertTrue(
            "event from previously blocked sender must be accepted after API 29 clearAllData",
            blockedSender.publicKeyHex in afterParticipants
        )
    }
}
