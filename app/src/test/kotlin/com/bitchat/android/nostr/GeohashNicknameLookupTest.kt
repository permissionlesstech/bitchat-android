package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class GeohashNicknameLookupTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    // Synthetic pubkeys that differ only in their displayed four-character suffix.
    private val firstAlice = "a".repeat(60) + "1111"
    private val secondAlice = "b".repeat(60) + "2222"
    private val bob = "c".repeat(60) + "3333"
    private val store = GeohashParticipantStore { 0L }
    private lateinit var scope: CoroutineScope
    private lateinit var state: ChatState
    private lateinit var repo: GeohashRepository

    @Before
    fun setUp() {
        AppStateStore.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        state = ChatState(scope)
        repo = GeohashRepository(application, state, DataManager(application), scope, store)
        store.cacheNickname(firstAlice, "fixture-alice")
        store.cacheNickname(secondAlice, "fixture-alice")
        store.cacheNickname(bob, "fixture-bob")
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppStateStore.clear()
    }

    @Test
    fun `unique nickname resolves to its pubkey`() {
        assertEquals(bob, repo.findPubkeyByNickname("fixture-bob"))
    }

    @Test
    fun `shared nickname without suffix is ambiguous`() {
        assertNull(repo.findPubkeyByNickname("fixture-alice"))
    }

    @Test
    fun `pubkey suffix disambiguates a shared nickname`() {
        assertEquals(secondAlice, repo.findPubkeyByNickname("fixture-alice#2222"))
        assertEquals(firstAlice, repo.findPubkeyByNickname("fixture-alice#1111"))
    }

    @Test
    fun `mismatched suffix or unknown nickname resolves to nothing`() {
        assertNull(repo.findPubkeyByNickname("fixture-alice#3333"))
        assertNull(repo.findPubkeyByNickname("anon"))
    }

    @Test
    fun `block target is restricted to the selected geohash and fails closed on collisions`() {
        store.updateParticipant("0000", firstAlice, 0)
        store.updateParticipant("0001", secondAlice, 0)

        assertEquals(firstAlice, repo.findPubkeyForGeohashBlock("0000", "fixture-alice"))
        assertNull(repo.findPubkeyForGeohashBlock("0000", "fixture-alice#2222"))

        store.updateParticipant("0000", secondAlice, 0)
        assertNull(repo.findPubkeyForGeohashBlock("0000", "fixture-alice"))
        assertEquals(secondAlice, repo.findPubkeyForGeohashBlock("0000", "fixture-alice#2222"))
    }

    @Test
    fun `anonymous sender can be blocked from a retained chat message without presence`() {
        val pubkey = "d".repeat(60) + "a1b2"
        state.setChannelMessages(mapOf("geo:0000" to listOf(BitchatMessage(
            sender = "anon",
            content = "fixture message",
            timestamp = Date(0),
            originalSender = "anon#a1b2",
            senderNostrPubkey = pubkey
        ))))

        assertEquals(pubkey, repo.findPubkeyForGeohashBlock("0000", "anon#A1B2"))
        assertNull(repo.findPubkeyForGeohashBlock("0001", "anon#a1b2"))
    }
}
