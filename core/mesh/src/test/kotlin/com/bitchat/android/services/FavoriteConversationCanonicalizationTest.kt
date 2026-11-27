package com.bitchat.android.services

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/**
 * A favourite's private messages are filed under its contact conversation, through the
 * favourites -> app state -> contact directory -> favourites loop, even where the chat session
 * never initialised the contact directory (a background mesh message, the watch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FavoriteConversationCanonicalizationTest {

    private val noiseKey = ByteArray(32) { 7 }
    private val peerID = ContactIdentityResolver.peerIdForNoiseKey(noiseKey)
    private val contactID = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey)

    private lateinit var favorites: FavoritesPersistenceService
    private lateinit var store: AppStateStore

    @Before
    fun setUp() {
        // Neither the directory's initialize nor a chat session runs: only the favourites start.
        val stores = TestMeshStores(RuntimeEnvironment.getApplication())
        favorites = stores.favorites.apply { initialize() }
        store = stores.appStateStore
    }

    @Test
    fun `a favourite's incoming message is filed under its contact conversation`() {
        favorites.updateFavoriteStatus(noiseKey, "fixture-peer", isFavorite = true)

        store.addPrivateMessage(peerID, message("m1"))

        assertEquals(setOf(contactID), store.privateMessages.value.keys)
    }

    @Test
    fun `favouriting a peer moves its earlier messages into the contact conversation`() {
        store.addPrivateMessage(peerID, message("m1"))
        assertEquals(setOf(peerID), store.privateMessages.value.keys)

        favorites.updateFavoriteStatus(noiseKey, "fixture-peer", isFavorite = true)

        assertEquals(setOf(contactID), store.privateMessages.value.keys)
        assertEquals(listOf("m1"), store.privateMessages.value[contactID].orEmpty().map { it.id })
    }

    private fun message(id: String) = BitchatMessage(
        id = id,
        sender = "fixture-peer",
        content = "hello",
        timestamp = Date(1_700_000_000_000L),
        isPrivate = true,
        senderPeerID = peerID,
    )
}
