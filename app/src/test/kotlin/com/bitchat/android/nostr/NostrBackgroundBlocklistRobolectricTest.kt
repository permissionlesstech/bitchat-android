package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrBackgroundBlocklistRobolectricTest {

    private lateinit var app: Application
    private lateinit var dispatcher: TestDispatcher
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppStateStore.clear()
        dispatcher = StandardTestDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppStateStore.clear()
        DataManager(app).clearAllData()
    }

    @Test
    @Config(sdk = [29])
    fun `DataManager clearAllData lifts geohash sender block through public event handling on API 29`() {
        val externalDataManager = DataManager(app)
        externalDataManager.clearAllData()

        val fixedNowMs = 1_700_000_000_000L
        val fixedNowSec = (fixedNowMs / 1_000L).toInt()
        val blockedSender = NostrIdentity.fromPrivateKey("11".repeat(32))
        val unblockedSender = NostrIdentity.fromPrivateKey("22".repeat(32))
        externalDataManager.addGeohashBlockedUser(blockedSender.publicKeyHex)

        val participantStore = GeohashParticipantStore(nowMillis = { fixedNowMs })
        val processor = NostrBackgroundEventProcessor(
            application = app,
            parentScope = scope,
            participantStore = participantStore,
            processingDispatcher = dispatcher,
            signatureVerificationDispatcher = dispatcher
        )
        val geohash = "u4pruy"

        val blockedBeforeWipe = NostrEvent(
            pubkey = blockedSender.publicKeyHex,
            createdAt = fixedNowSec,
            kind = NostrKind.GEOHASH_PRESENCE,
            tags = listOf(listOf("g", geohash)),
            content = ""
        ).sign(blockedSender.privateKeyHex)
        val unblockedBeforeWipe = NostrEvent(
            pubkey = unblockedSender.publicKeyHex,
            createdAt = fixedNowSec,
            kind = NostrKind.GEOHASH_PRESENCE,
            tags = listOf(listOf("g", geohash)),
            content = ""
        ).sign(unblockedSender.privateKeyHex)

        processor.onGeohashMessage(blockedBeforeWipe, geohash)
        processor.onGeohashMessage(unblockedBeforeWipe, geohash)
        // Drain every coroutine (including signature verification) deterministically before asserting.
        dispatcher.scheduler.advanceUntilIdle()

        val beforeParticipants = participantStore.snapshots.value.participants[geohash].orEmpty()
        assertTrue(
            "unblocked sender event must be recorded prior to wipe",
            unblockedSender.publicKeyHex in beforeParticipants
        )
        assertFalse(
            "blocked sender event must be dropped prior to wipe",
            blockedSender.publicKeyHex in beforeParticipants
        )

        // Panic wipe through DataManager.clearAllData() calls SharedPreferences.Editor.clear(),
        // which omits OnSharedPreferenceChangeListener callbacks on Android API 26-29.
        externalDataManager.clearAllData()

        val afterWipe = NostrEvent(
            pubkey = blockedSender.publicKeyHex,
            createdAt = fixedNowSec + 1,
            kind = NostrKind.GEOHASH_PRESENCE,
            tags = listOf(listOf("g", geohash)),
            content = ""
        ).sign(blockedSender.privateKeyHex)
        processor.onGeohashMessage(afterWipe, geohash)
        dispatcher.scheduler.advanceUntilIdle()

        val afterParticipants = participantStore.snapshots.value.participants[geohash].orEmpty()
        assertTrue(
            "event from previously blocked sender must be accepted after API 29 clearAllData",
            blockedSender.publicKeyHex in afterParticipants
        )
    }
}
