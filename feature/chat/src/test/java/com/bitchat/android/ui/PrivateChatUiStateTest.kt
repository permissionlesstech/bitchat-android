package com.bitchat.android.ui

import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactDirectory
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateChatUiStateTest {

    private val meshPeer = "0102030405060708"
    private val contactID = "contact_synthetic"
    private val nostrConversation = "nostr_synthetic"

    private class FakeResolvers : PrivateChatResolvers {
        var contacts = mapOf<String, ContactDirectory.ContactResolution>()
        var favorites = mapOf<String, FavoriteRelationship>()
        var geohashes = mapOf<String, String>()
        var nostrPubkeys = mapOf<String, String>()
        var verification = mapOf<String, String>()

        override fun contact(conversationID: String) =
            contacts[conversationID] ?: resolution(conversationID, meshPeerID = null)
        override fun favoriteStatus(conversationID: String) = favorites[conversationID]
        override fun fingerprintDisplayName(conversationID: String) = "fallback-name"
        override fun geohashOf(conversationID: String) = geohashes[conversationID]
        override fun nostrPubkeyOf(conversationID: String) = nostrPubkeys[conversationID]
        override fun geohashDisplayName(nostrPubkeyHex: String, geohash: String) =
            "geo-name-$geohash"
        override fun fingerprintFromContactConversationID(conversationID: String): String? = null
        override fun verificationFingerprint(conversationID: String) = verification[conversationID]
        override fun isFavorite(conversationID: String) = false
    }

    private fun inputs(
        conversationID: String,
        privateChats: Map<String, List<BitchatMessage>> = emptyMap(),
        peerNicknames: Map<String, String> = emptyMap(),
        connectedPeers: List<String> = emptyList(),
        peerDirect: Map<String, Boolean> = emptyMap(),
        peerSessionStates: Map<String, String> = emptyMap(),
        favoritePeers: Set<String> = emptySet(),
        peerFingerprints: Map<String, String> = emptyMap(),
        verifiedFingerprints: Set<String> = emptySet(),
    ) = PrivateChatInputs(
        conversationID = conversationID,
        privateChats = privateChats,
        peerNicknames = peerNicknames,
        nickname = "me",
        connectedPeers = connectedPeers,
        peerDirect = peerDirect,
        peerSessionStates = peerSessionStates,
        favoritePeers = favoritePeers,
        peerFavoritedUs = emptySet(),
        peerFingerprints = peerFingerprints,
        verifiedFingerprints = verifiedFingerprints,
        wifiAwarePeerIDs = emptySet(),
    )

    @Test
    fun `a connected mesh peer shows its nickname, session and verification`() {
        val resolvers = FakeResolvers().apply {
            verification = mapOf(meshPeer to "fp-a")
        }
        val state = privateChatUiStateFor(
            inputs(
                conversationID = meshPeer,
                privateChats = mapOf(meshPeer to listOf(msg("m1"))),
                peerNicknames = mapOf(meshPeer to "alice"),
                connectedPeers = listOf(meshPeer),
                peerDirect = mapOf(meshPeer to true),
                peerSessionStates = mapOf(meshPeer to "established"),
                favoritePeers = setOf("fp-a"),
                peerFingerprints = mapOf(meshPeer to "fp-a"),
                verifiedFingerprints = setOf("fp-a"),
            ),
            resolvers,
        )

        assertEquals("alice", state.titleText)
        assertEquals(listOf("m1"), state.messages.map { it.id })
        assertEquals("established", state.sessionState)
        assertTrue(state.isDirect)
        assertTrue(state.isVerified)
        assertTrue(state.isFavorite)
        assertFalse(state.isNostrReachableFavorite)
    }

    @Test
    fun `a contact reads its name, history and session through its mesh peer`() {
        val resolvers = FakeResolvers().apply {
            contacts = mapOf(contactID to resolution(contactID, meshPeerID = meshPeer))
        }
        val state = privateChatUiStateFor(
            inputs(
                conversationID = contactID,
                privateChats = mapOf(contactID to listOf(msg("c1"))),
                peerNicknames = mapOf(meshPeer to "bob"),
                connectedPeers = listOf(meshPeer),
                peerSessionStates = mapOf(meshPeer to "handshaking"),
            ),
            resolvers,
        )

        assertEquals("bob", state.titleText)
        assertEquals(listOf("c1"), state.messages.map { it.id })
        assertEquals("handshaking", state.sessionState)
    }

    @Test
    fun `a geohash DM is titled by its geohash and the sender's geohash name`() {
        val resolvers = FakeResolvers().apply {
            geohashes = mapOf(nostrConversation to "s0000")
            nostrPubkeys = mapOf(nostrConversation to "ab".repeat(32))
            verification = mapOf(nostrConversation to "fp-n")
        }
        val state = privateChatUiStateFor(
            inputs(conversationID = nostrConversation, verifiedFingerprints = setOf("fp-n")),
            resolvers,
        )

        assertEquals("#s0000/@geo-name-s0000", state.titleText)
        assertTrue(state.isNostrPeer)
        assertFalse("a Nostr DM is never shown as verified", state.isVerified)
    }

    @Test
    fun `a mutual favourite off the mesh is reachable over Nostr`() {
        val resolvers = FakeResolvers().apply {
            favorites = mapOf(meshPeer to favorite(nickname = "carol", mutual = true))
        }
        val offMesh = privateChatUiStateFor(inputs(conversationID = meshPeer), resolvers)
        val onMesh = privateChatUiStateFor(
            inputs(conversationID = meshPeer, connectedPeers = listOf(meshPeer)),
            resolvers,
        )

        assertTrue(offMesh.isNostrReachableFavorite)
        assertEquals("carol", offMesh.titleText)
        assertFalse("on the mesh it is reached directly", onMesh.isNostrReachableFavorite)
    }

    @Test
    fun `an unknown peer falls back to its fingerprint's name, never "Unknown"`() {
        val resolvers = FakeResolvers().apply {
            favorites = mapOf(meshPeer to favorite(nickname = "Unknown", mutual = false))
        }
        val state = privateChatUiStateFor(inputs(conversationID = meshPeer), resolvers)

        assertEquals("fallback-name", state.titleText)
    }

    private fun msg(id: String) = BitchatMessage(
        id = id,
        sender = "alice",
        content = "hello",
        timestamp = Date(0),
    )

    private fun favorite(nickname: String, mutual: Boolean) = FavoriteRelationship(
        peerNoisePublicKey = ByteArray(32) { 1 },
        peerNostrPublicKey = "npub1synthetic",
        peerNickname = nickname,
        isFavorite = true,
        theyFavoritedUs = mutual,
        favoritedAt = Date(0),
        lastUpdated = Date(0),
    )

    private companion object {
        fun resolution(conversationID: String, meshPeerID: String?) =
            ContactDirectory.ContactResolution(
                conversationID = conversationID,
                meshPeerID = meshPeerID,
                noisePublicKey = null,
                nostrPubkey = null,
                displayName = null,
                isMutualFavorite = false,
            )
    }
}
