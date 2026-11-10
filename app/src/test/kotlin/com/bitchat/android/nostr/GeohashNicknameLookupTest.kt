package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
class GeohashNicknameLookupTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    // Synthetic pubkeys that differ only in their displayed four-character suffix.
    private val firstAlice = "a".repeat(60) + "1111"
    private val secondAlice = "b".repeat(60) + "2222"
    private val bob = "c".repeat(60) + "3333"
    private val store = GeohashParticipantStore { 0L }
    private lateinit var scope: CoroutineScope
    private lateinit var repo: GeohashRepository

    @Before
    fun setUp() {
        AppStateStore.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repo = GeohashRepository(application, ChatState(scope), DataManager(application), scope, store)
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
}
