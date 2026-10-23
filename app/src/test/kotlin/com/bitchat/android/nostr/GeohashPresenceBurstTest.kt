package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeohashPresenceBurstTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val state = ChatState(scope)
    private val dataManager = DataManager(application)
    private val repo = GeohashRepository(application, state, dataManager, scope)
    private val handler = GeohashMessageHandler(
        application, repo, scope, dataManager, { _, _ -> }, dispatcher
    )
    // A synthetic fixture channel; no device location or relay traffic is used.
    private val geohash = "s000"

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `presence bursts coalesce UI snapshots including after channel reentry`() {
        val snapshots = mutableListOf<Int>()
        val collection = scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            state.geohashPeople.drop(1).collect { snapshots += it.size }
        }
        val identities = (1..50).map { index ->
            NostrIdentity.fromPrivateKey(index.toString(16).padStart(64, '0'))
        }
        val timestamp = (System.currentTimeMillis() / 1000).toInt() - 10
        repeat(2) { round ->
            repo.setCurrentGeohash(null)
            repo.refreshGeohashPeople()
            repo.setCurrentGeohash(geohash)
            repo.refreshGeohashPeople()
            snapshots.clear()
            identities.forEachIndexed { index, identity ->
                handler.onEvent(
                    NostrEvent(
                        pubkey = identity.publicKeyHex,
                        createdAt = timestamp + round,
                        kind = NostrKind.GEOHASH_PRESENCE,
                        tags = listOf(listOf("g", geohash), listOf("n", "test-$index")),
                        content = ""
                    ).sign(identity.privateKeyHex),
                    geohash
                )
            }
            scope.advanceUntilIdle()

            assertEquals(50, state.geohashPeople.value.size)
            assertEquals(50, state.geohashParticipantCounts.value[geohash])
            assertTrue(state.geohashPeople.value.all { it.displayName.startsWith("test-") })
            assertTrue(
                "A relay burst produced ${snapshots.size} UI snapshots on entry ${round + 1}",
                snapshots.size <= 1
            )
        }
        collection.cancel()
    }

    @Test
    fun `pending presence publication follows channel selection`() {
        repo.setCurrentGeohash(geohash)
        repo.updateParticipant(geohash, "fixture-person", Date())
        repo.cacheNickname("fixture-person", "test-person")
        repo.setCurrentGeohash(null)
        repo.refreshGeohashPeople()

        scope.advanceUntilIdle()

        assertTrue(state.geohashPeople.value.isEmpty())
        assertEquals(1, state.geohashParticipantCounts.value[geohash])
    }

    @Test
    fun `clearing presence cancels pending publication without restoring counts`() {
        repo.setCurrentGeohash(geohash)
        repo.updateParticipant(geohash, "fixture-person", Date())
        repo.clearAll()

        scope.advanceUntilIdle()

        assertTrue(state.geohashPeople.value.isEmpty())
        assertTrue(state.geohashParticipantCounts.value.isEmpty())
    }

    @Test
    fun `batched presence retains newest timestamps and excludes blocked people`() {
        val now = System.currentTimeMillis()
        dataManager.addGeohashBlockedUser("fixture-blocked")
        repo.setCurrentGeohash(geohash)
        repo.updateParticipant(geohash, "fixture-person", Date(now - 1_000))
        repo.cacheNickname("fixture-person", "test-person")
        repo.updateParticipant(geohash, "fixture-person", Date(now - 10_000))
        repo.updateParticipant(geohash, "fixture-blocked", Date(now))
        repo.updateParticipant(geohash, "fixture-expired", Date(now - 600_000))

        scope.advanceUntilIdle()

        val person = state.geohashPeople.value.single()
        assertEquals("test-person", person.displayName)
        assertEquals(now - 1_000, person.lastSeen.time)
        assertEquals(1, state.geohashParticipantCounts.value[geohash])
    }
}
