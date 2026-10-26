package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.sectionGeohashPeople
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeohashParticipantTrackingTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    // Synthetic channel near the coordinate origin; no location provider or relay is used.
    private val geohash = "s00000"
    private val identity = NostrIdentity.fromSeed("geohash-participant-fixture")
    private var now = System.currentTimeMillis() / 1000 * 1000
    private val store = GeohashParticipantStore { now }
    private lateinit var scope: CoroutineScope
    private lateinit var state: ChatState
    private lateinit var dataManager: DataManager
    private lateinit var repo: GeohashRepository
    private lateinit var processor: NostrBackgroundEventProcessor

    @Before
    fun setUp() {
        AppStateStore.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        state = ChatState(scope)
        dataManager = DataManager(application)
        repo = GeohashRepository(application, state, dataManager, scope, store)
        repo.setCurrentGeohash(geohash)
        repo.observeParticipants()
        processor = NostrBackgroundEventProcessor(application, scope, store)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dataManager.removeGeohashBlockedUser(identity.publicKeyHex)
        AppStateStore.clear()
    }

    private fun chatEvent() = NostrEvent(
        pubkey = identity.publicKeyHex,
        createdAt = (now / 1000).toInt(),
        kind = NostrKind.EPHEMERAL_EVENT,
        tags = listOf(listOf("g", geohash), listOf("n", "fixture-speaker"), listOf("t", "teleport")),
        content = "synthetic participant message"
    ).sign(identity.privateKeyHex)

    private suspend fun receiveChat() {
        val event = chatEvent()
        processor.onGeohashMessage(event, geohash)
        withTimeout(5_000) {
            AppStateStore.channelMessages.first { messages ->
                messages["geo:$geohash"].orEmpty().any { it.id == event.id }
            }
            state.geohashPeople.first { it.singleOrNull()?.displayName == "fixture-speaker" }
            state.geohashParticipantCounts.first { it[geohash] == 1 }
        }
    }

    @Test
    fun `service chat adds a visible named participant without a heartbeat`() = runBlocking {
        receiveChat()

        val people = withTimeout(5_000) {
            state.geohashPeople.first { it.singleOrNull()?.displayName == "fixture-speaker" }
        }
        assertEquals(identity.publicKeyHex, people.single().id)
        assertEquals(1, state.geohashParticipantCounts.value[geohash])
        val sections = sectionGeohashPeople(people, null, false, state.teleportedGeo.value)
        assertTrue(sections.onLocation.isEmpty())
        assertEquals(people, sections.teleportedIn)
    }

    @Test
    fun `chat names a heartbeat identity and later heartbeats retain the name`() = runBlocking {
        repo.updateParticipant(geohash, identity.publicKeyHex, Date(now - 1_000))
        withTimeout(5_000) { state.geohashPeople.first { it.size == 1 } }
        assertEquals("anon", state.geohashPeople.value.single().displayName)

        receiveChat()
        repo.updateParticipant(geohash, identity.publicKeyHex, Date(now))

        assertEquals("fixture-speaker", state.geohashPeople.value.single().displayName)
        assertEquals(1, repo.geohashParticipantCount(geohash))
    }

    @Test
    fun `a recreated UI hydrates chat participants and follows shared clearing`() = runBlocking {
        receiveChat()

        val recreatedState = ChatState(scope)
        val recreatedRepo = GeohashRepository(application, recreatedState, dataManager, scope, store)
        recreatedRepo.setCurrentGeohash(geohash)
        recreatedRepo.observeParticipants()
        withTimeout(5_000) {
            recreatedState.geohashPeople.first { it.singleOrNull()?.displayName == "fixture-speaker" }
        }

        assertEquals("fixture-speaker", recreatedState.geohashPeople.value.single().displayName)
        assertEquals(setOf(identity.publicKeyHex), recreatedState.teleportedGeo.value)

        repo.clearAll()
        withTimeout(5_000) {
            recreatedState.geohashPeople.first { it.isEmpty() }
            recreatedState.geohashParticipantCounts.first { it.isEmpty() }
        }

        assertTrue(recreatedState.geohashPeople.value.isEmpty())
        assertTrue(recreatedState.teleportedGeo.value.isEmpty())
        assertTrue(recreatedState.geohashParticipantCounts.value.isEmpty())
    }

    @Test
    fun `shared chat participants still respect UI blocking and channel selection`() = runBlocking {
        receiveChat()
        dataManager.addGeohashBlockedUser(identity.publicKeyHex)
        repo.refreshGeohashPeople()
        repo.updateReactiveParticipantCounts()

        assertTrue(state.geohashPeople.value.isEmpty())
        assertEquals(0, repo.geohashParticipantCount(geohash))
        assertEquals(0, state.geohashParticipantCounts.value[geohash])

        dataManager.removeGeohashBlockedUser(identity.publicKeyHex)
        repo.setCurrentGeohash("s000000")
        repo.refreshGeohashPeople()
        assertTrue(state.geohashPeople.value.isEmpty())

        repo.setCurrentGeohash(geohash)
        repo.refreshGeohashPeople()
        assertEquals(identity.publicKeyHex, state.geohashPeople.value.single().id)
    }

    @Test
    fun `chat participants expire from both the list and counts after five minutes`() = runBlocking {
        receiveChat()

        now += 300_001
        repo.refreshGeohashPeople()
        withTimeout(5_000) { state.geohashParticipantCounts.first { it[geohash] == 0 } }

        assertTrue(state.geohashPeople.value.isEmpty())
        assertEquals(0, state.geohashParticipantCounts.value[geohash])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `service participant bursts publish one foreground snapshot per interval`() = runTest {
        val sharedStore = GeohashParticipantStore { now }
        val foregroundState = ChatState(backgroundScope)
        val foregroundRepo = GeohashRepository(application, foregroundState, dataManager, backgroundScope, sharedStore)
        val serviceRepo = GeohashRepository(application, ChatState(backgroundScope), dataManager, backgroundScope, sharedStore)
        foregroundRepo.setCurrentGeohash(geohash)
        foregroundRepo.observeParticipants()
        val snapshots = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            foregroundState.geohashPeople.drop(1).collect { snapshots += it.size }
        }
        runCurrent()

        repeat(50) { index ->
            serviceRepo.updateParticipant(geohash, "fixture-$index", Date(now))
            serviceRepo.cacheNickname("fixture-$index", "speaker-$index")
        }
        runCurrent()
        assertTrue(snapshots.isEmpty())

        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf(50), snapshots)
        assertEquals(50, foregroundState.geohashParticipantCounts.value[geohash])
    }
}
