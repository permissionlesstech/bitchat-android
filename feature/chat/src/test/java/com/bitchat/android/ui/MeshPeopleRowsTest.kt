package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ContactIdentityResolver
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Characterizes the mesh people card (inventory P1, P4, P5, P8, P12-P22, P25). */
class MeshPeopleRowsTest {

    private val p1 = "0123456789abcdef"
    private val p2 = "fedcba9876543210"
    private val p3 = "aaaaaaaaaaaaaaaa"
    private val now = PEER_LIST_NOW_MILLIS

    private fun message(sender: String, atMillis: Long = now) = BitchatMessage(
        sender = sender,
        content = "hello",
        timestamp = Date(atMillis),
        isPrivate = true,
    )

    private fun conversation(id: String, aliases: Set<String>) = ConversationSummary(
        conversationID = id,
        displayName = id,
        unreadCount = 0,
        latestMessageAt = now,
        latestActivityOrder = now,
        latestMessageType = BitchatMessageType.Message,
        latestMessagePreview = "",
        transport = DirectMessageTransport.MESH,
        nostrPubkey = null,
        identityAliases = aliases,
    )

    private fun rows(
        connectedPeers: List<String>,
        lookups: FakePeerListLookups = FakePeerListLookups(),
        peerNicknames: Map<String, String> = emptyMap(),
        peerDirect: Map<String, Boolean> = emptyMap(),
        wifiAwarePeerIDs: Set<String> = emptySet(),
        favoritePeers: Set<String> = emptySet(),
        peerFavoritedUs: Set<String> = emptySet(),
        peerFingerprints: Map<String, String> = emptyMap(),
        verifiedFingerprints: Set<String> = emptySet(),
        unreadPrivateMessages: Set<String> = emptySet(),
        privateChats: Map<String, List<BitchatMessage>> = emptyMap(),
        selectedPrivatePeer: String? = null,
        nickname: String = "carol",
        excludedIdentityAliases: Set<String> = emptySet(),
    ) = buildMeshPeopleRows(
        connectedPeers = connectedPeers,
        peerNicknames = peerNicknames,
        peerDirect = peerDirect,
        wifiAwarePeerIDs = wifiAwarePeerIDs,
        favoritePeers = favoritePeers,
        peerFavoritedUs = peerFavoritedUs,
        peerFingerprints = peerFingerprints,
        verifiedFingerprints = verifiedFingerprints,
        unreadPrivateMessages = unreadPrivateMessages,
        privateChats = privateChats,
        selectedPrivatePeer = selectedPrivatePeer,
        nickname = nickname,
        excludedIdentityAliases = excludedIdentityAliases,
        lookups = lookups,
    )

    // Review Focus 1 / P4, P5, P8 / Q-P2

    @Test
    fun peerWithConversationAlias_hiddenFromPeopleAndCount() {
        val noiseHex = ContactIdentityResolver.noiseKeyHex(syntheticNoiseKey(0x11))
        val lookups = FakePeerListLookups(aliases = mapOf(p1 to setOf(p1, noiseHex.uppercase())))
        val aliases = conversationIdentityAliases(
            listOf(conversation("contact_x", setOf(noiseHex)))
        )

        val visible = visibleConnectedPeers(listOf(p1, p2, p3), aliases, lookups)

        assertEquals(listOf(p2, p3), visible)
        assertEquals(1, meshPeopleCount(visible, myPeerID = p3))
        assertEquals(listOf(p3, p2), rows(visible, lookups).map { it.key })
    }

    @Test
    fun aliasResolutionFails_peerIdItselfIsTheOnlyAlias() {
        val lookups = FakePeerListLookups(aliasFailure = true)

        val visible = visibleConnectedPeers(listOf(p1, p2), setOf(p1), lookups)

        assertEquals(listOf(p2), visible)
    }

    @Test
    fun peopleCount_excludesSelfAndOfflineFavourites_currentBehaviour_QP2() {
        val favourite = favoriteRelationship(syntheticNoiseKey(0x22), nickname = "bob")
        val lookups = FakePeerListLookups(ourFavoritesList = listOf(favourite))
        val visible = listOf(p1, p2)

        val shown = rows(visible, lookups)

        assertEquals(3, shown.size)
        assertEquals(1, meshPeopleCount(visible, myPeerID = p2))
    }

    @Test
    fun wifiAwarePeerIDs_areTheConnectedMapKeys() {
        assertEquals(setOf(p1, p2), wifiAwarePeerIDs(mapOf(p1 to Unit, p2 to null)))
    }

    // P18

    @Test
    fun sortOrder_unreadThenNewestDmThenFavouriteThenName() {
        val peers = listOf("1111111111111111", "2222222222222222", "3333333333333333",
            "4444444444444444", "5555555555555555")
        val shown = rows(
            connectedPeers = peers,
            lookups = FakePeerListLookups(favorites = setOf("4444444444444444")),
            peerNicknames = mapOf(
                "1111111111111111" to "zed",
                "2222222222222222" to "yan",
                "3333333333333333" to "xia",
                "4444444444444444" to "wes",
                "5555555555555555" to "Abe",
            ),
            unreadPrivateMessages = setOf("1111111111111111"),
            privateChats = mapOf(
                "2222222222222222" to listOf(message("yan", now - 10_000)),
                "3333333333333333" to listOf(message("xia", now - 5_000)),
            ),
        )

        assertEquals(
            listOf("zed", "xia", "yan", "wes", "Abe"),
            shown.map { it.displayName }
        )
    }

    @Test
    fun nameWithoutNickname_isPeerIdPrefixOrLastDmSender() {
        val shown = rows(
            connectedPeers = listOf(p1, p2),
            privateChats = mapOf(p2 to listOf(message("bob"))),
        )

        assertEquals(listOf("bob", p1.take(12)), shown.map { it.displayName })
    }

    // Review Focus 2 / P19

    @Test
    fun duplicateBaseNames_getSuffix() {
        val shown = rows(
            connectedPeers = listOf(p1, p2, p3),
            peerNicknames = mapOf(p1 to "alice#0a1b", p2 to "bob#2c3d", p3 to "alice#4e5f"),
        )

        val byPeer = shown.associateBy { it.peerID }
        assertTrue(byPeer.getValue(p1).showHashSuffix)
        assertEquals("alice", byPeer.getValue(p1).baseName)
        assertEquals("#0a1b", byPeer.getValue(p1).suffix)
        assertEquals("#4e5f", byPeer.getValue(p3).suffix)
        assertFalse(byPeer.getValue(p2).showHashSuffix)
        assertEquals("bob", byPeer.getValue(p2).baseName)
        assertEquals("", byPeer.getValue(p2).suffix)
    }

    @Test
    fun duplicateBaseNames_countOfflineFavouritesToo() {
        val favourite = favoriteRelationship(syntheticNoiseKey(0x22), nickname = "alice#9f8e")
        val shown = rows(
            connectedPeers = listOf(p1),
            lookups = FakePeerListLookups(ourFavoritesList = listOf(favourite)),
            peerNicknames = mapOf(p1 to "alice#0a1b"),
        )

        assertEquals(listOf("#0a1b", "#9f8e"), shown.map { it.suffix })
    }

    // Q-P3

    @Test
    fun youRow_comparesPeerIdWithNickname_currentBehaviour_QP3() {
        val shown = rows(
            connectedPeers = listOf(p1, p2, p3),
            peerNicknames = mapOf(p1 to "alice", p2 to "You", p3 to "bob"),
            nickname = "alice",
        ).associateBy { it.peerID }

        // Same nickname as ours: not treated as me, because the peer ID is compared.
        assertEquals("alice", shown.getValue(p1).displayName)
        assertFalse(shown.getValue(p1).isMe)
        // A peer literally named "You" renders as me.
        assertTrue(shown.getValue(p2).isMe)
        assertFalse(shown.getValue(p3).isMe)

        // Only a peer ID equal to our nickname becomes "You".
        val idAsNickname = rows(listOf(p1), peerNicknames = mapOf(p1 to "bob"), nickname = p1)
        assertEquals("You", idAsNickname.single().displayName)
        assertTrue(idAsNickname.single().isMe)
    }

    // Q-P6 / P21 unread

    @Test
    fun unreadBadge_countsAllIncoming_currentBehaviour_QP6() {
        val shown = rows(
            connectedPeers = listOf(p1),
            unreadPrivateMessages = setOf(p1),
            privateChats = mapOf(
                p1 to listOf(message("bob"), message("bob"), message("alice"), message("bob"))
            ),
            nickname = "alice",
        )

        // Three incoming messages, however many are actually unread.
        assertEquals(3, shown.single().unreadCount)
    }

    @Test
    fun unreadFlagWithoutMessages_showsOne_andNoFlagShowsZero() {
        val flagged = rows(listOf(p1), unreadPrivateMessages = setOf(p1))
        val unflagged = rows(listOf(p1), privateChats = mapOf(p1 to listOf(message("bob"))))

        assertEquals(1, flagged.single().unreadCount)
        assertEquals(0, unflagged.single().unreadCount)
    }

    @Test
    fun unreadUnderNoiseKey_countsMessagesStoredUnderIt() {
        val noiseHex = ContactIdentityResolver.noiseKeyHex(syntheticNoiseKey(0x11))
        val shown = rows(
            connectedPeers = listOf(p1),
            lookups = FakePeerListLookups(cachedNoiseKeys = mapOf(p1 to noiseHex)),
            unreadPrivateMessages = setOf(noiseHex),
            privateChats = mapOf(noiseHex to listOf(message("bob"), message("bob"))),
        )

        assertEquals(2, shown.single().unreadCount)
    }

    // P12-P14

    @Test
    fun favourite_usesFingerprintWhenKnown_elseLookup() {
        val lookups = FakePeerListLookups(favorites = setOf(p2))
        val shown = rows(
            connectedPeers = listOf(p1, p2),
            lookups = lookups,
            favoritePeers = setOf("fp1"),
            peerFingerprints = mapOf(p1 to "fp1"),
        ).associateBy { it.peerID }

        assertTrue(shown.getValue(p1).isFavorite)
        assertTrue(shown.getValue(p2).isFavorite)
        assertEquals(listOf(p2), lookups.isFavoriteCalls)
    }

    @Test
    fun theyFavoritedUs_fingerprintThenStoredRelationship_failureIsFalse() {
        val relationship = favoriteRelationship(
            syntheticNoiseKey(0x22),
            nickname = "bob",
            theyFavoritedUs = true
        )
        val shown = rows(
            connectedPeers = listOf(p1, p2, p3),
            lookups = FakePeerListLookups(favoriteStatuses = mapOf(p2 to relationship)),
            peerFavoritedUs = setOf("fp1"),
            peerFingerprints = mapOf(p1 to "fp1"),
        ).associateBy { it.peerID }

        assertTrue(shown.getValue(p1).theyFavoritedUs)
        assertTrue(shown.getValue(p2).theyFavoritedUs)
        assertFalse(shown.getValue(p3).theyFavoritedUs)

        val failing = peerTheyFavoritedUsStates(
            listOf(p1),
            emptySet(),
            emptyMap(),
            FakePeerListLookups(favoriteStatusFailure = true)
        )
        assertEquals(mapOf(p1 to false), failing)
    }

    @Test
    fun verified_usesDisplayFingerprint_neverForNostrAliases() {
        val lookups = FakePeerListLookups(
            displayFingerprints = mapOf(p1 to "fp1", "nostr_$p2" to "fp1")
        )

        assertEquals(
            mapOf(p1 to true, p3 to false, "nostr_$p2" to false),
            peerVerifiedStates(listOf(p1, p3, "nostr_$p2"), setOf("fp1"), lookups)
        )
    }

    // P20 / P22 / Q-P8

    @Test
    fun offlineFavourites_appendedInOrder_exceptConversationsAndConnected() {
        val inConversation = favoriteRelationship(syntheticNoiseKey(0x11), nickname = "bob")
        val connectedByNoise = favoriteRelationship(syntheticNoiseKey(0x22), nickname = "bob")
        val connectedByNostr = favoriteRelationship(
            syntheticNoiseKey(0x33),
            nickname = "bob",
            nostrPublicKey = "cd".repeat(32)
        )
        val offlineA = favoriteRelationship(syntheticNoiseKey(0x44), nickname = "alice")
        val offlineB = favoriteRelationship(syntheticNoiseKey(0x55), nickname = "bob")
        val lookups = FakePeerListLookups(
            ourFavoritesList = listOf(
                inConversation, connectedByNoise, offlineB, connectedByNostr, offlineA
            ),
            peerInfos = mapOf(p1 to meshPeerInfo(p1, noiseKey = syntheticNoiseKey(0x22))),
            nostrByPeerID = mapOf(p2 to "CD".repeat(32)),
        )

        val shown = rows(
            connectedPeers = listOf(p1, p2),
            lookups = lookups,
            excludedIdentityAliases = setOf(
                ContactIdentityResolver.noiseKeyHex(syntheticNoiseKey(0x11))
            ),
        )

        assertEquals(
            listOf(
                p1,
                p2,
                ContactIdentityResolver.noiseKeyHex(syntheticNoiseKey(0x55)),
                ContactIdentityResolver.noiseKeyHex(syntheticNoiseKey(0x44)),
            ),
            shown.map { it.key }
        )
    }

    @Test
    fun offlineFavourite_row_currentBehaviour_QP8() {
        val nostrHex = "ab".repeat(32)
        val mutual = favoriteRelationship(
            syntheticNoiseKey(0x44),
            nickname = "alice",
            nostrPublicKey = nostrHex,
            theyFavoritedUs = true
        )
        val noiseHex = ContactIdentityResolver.noiseKeyHex(syntheticNoiseKey(0x44))
        val nostrAlias = "nostr_${nostrHex.take(16)}"
        val lookups = FakePeerListLookups(
            ourFavoritesList = listOf(mutual),
            nostrByNoiseHex = mapOf(noiseHex to nostrHex),
        )

        val row = rows(
            connectedPeers = emptyList(),
            lookups = lookups,
            unreadPrivateMessages = setOf(nostrAlias),
            privateChats = mapOf(nostrAlias to listOf(message("alice"), message("carol"))),
            verifiedFingerprints = setOf(
                ContactIdentityResolver.fingerprintHex(syntheticNoiseKey(0x44))
            ),
            selectedPrivatePeer = noiseHex,
        ).single()

        assertEquals(noiseHex, row.key)
        assertEquals("alice", row.displayName)
        assertFalse(row.isConnected)
        assertFalse(row.isDirect)
        assertTrue(row.isFavorite)
        assertTrue(row.theyFavoritedUs)
        assertTrue(row.isVerified)
        assertTrue(row.showNostrGlobe)
        assertTrue(row.isSelected)
        assertEquals(1, row.unreadCount)
        // Never a connected peer: P20 already dropped those (Q-P8).
        assertEquals(noiseHex, row.openPeerID)
    }

    @Test
    fun offlineFavourite_nameFromPeerNicknamesFirst_globeOnlyWhenMutualWithNostr() {
        val notMutual = favoriteRelationship(
            syntheticNoiseKey(0x44),
            nickname = "alice",
            nostrPublicKey = "ab".repeat(32)
        )
        val noiseHex = ContactIdentityResolver.noiseKeyHex(syntheticNoiseKey(0x44))

        val row = rows(
            connectedPeers = emptyList(),
            lookups = FakePeerListLookups(ourFavoritesList = listOf(notMutual)),
            peerNicknames = mapOf(noiseHex to "bob"),
        ).single()

        assertEquals("bob", row.displayName)
        assertFalse(row.showNostrGlobe)
        assertEquals(0, row.unreadCount)
    }

    // P21 / P25

    @Test
    fun connectedRow_directSelectedWifiAwareAndColour() {
        val lookups = FakePeerListLookups(
            canonical = mapOf(p1 to "contact_x"),
            peerInfos = mapOf(p2 to meshPeerInfo(p2, isDirect = true)),
        )
        val shown = rows(
            connectedPeers = listOf(p1, p2, p3),
            lookups = lookups,
            peerNicknames = mapOf(p1 to "alice", p2 to "bob", p3 to "carl"),
            peerDirect = mapOf(p1 to false, p3 to true),
            wifiAwarePeerIDs = setOf(p3),
            selectedPrivatePeer = "contact_x",
        ).associateBy { it.peerID }

        assertFalse(shown.getValue(p1).isDirect) // peerDirect wins over PeerInfo
        assertTrue(shown.getValue(p2).isDirect) // falls back to PeerInfo
        assertTrue(shown.getValue(p3).isDirect)
        assertTrue(shown.getValue(p3).isWifiAware)
        assertTrue(shown.getValue(p1).isSelected) // by canonical conversation ID
        assertFalse(shown.getValue(p2).isSelected)
        assertTrue(shown.values.all { it.isConnected && !it.showNostrGlobe })
        assertEquals(p1, shown.getValue(p1).openPeerID)
        assertEquals(PeerIdentity.mesh(p1).stableKey, shown.getValue(p1).colorKey)
    }
}
