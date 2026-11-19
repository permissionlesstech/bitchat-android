package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationStoreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Characterizes the conversations and channels sections (inventory P2, P3, P6, P7, P9, P10, P23, P24). */
class ConversationRowModelTest {

    private val p1 = "0123456789abcdef"
    private val p2 = "fedcba9876543210"
    private val now = PEER_LIST_NOW_MILLIS

    private fun conversation(
        id: String,
        displayName: String = "alice",
        preview: String = "hello",
        type: BitchatMessageType = BitchatMessageType.Message,
        outgoing: Boolean = false,
        draft: String? = null,
        isConnected: Boolean = false,
        connectedPeerID: String? = null,
        transport: DirectMessageTransport = DirectMessageTransport.MESH,
        nostrPubkey: String? = null,
        aliases: Set<String> = setOf(id),
        unreadCount: Int = 0,
    ) = ConversationSummary(
        conversationID = id,
        displayName = displayName,
        unreadCount = unreadCount,
        latestMessageAt = now,
        latestActivityOrder = now,
        latestMessageType = type,
        latestMessagePreview = preview,
        latestMessageIsOutgoing = outgoing,
        transport = transport,
        nostrPubkey = nostrPubkey,
        identityAliases = aliases,
        isConnected = isConnected,
        connectedPeerID = connectedPeerID,
        draft = draft,
    )

    private val noFlags = ConversationFavoriteFlags(
        isFavorite = false,
        theyFavoritedUs = false,
        isVerified = false
    )

    private fun row(
        conversation: ConversationSummary,
        direct: Set<String> = emptySet(),
        wifiAware: Set<String> = emptySet(),
        lookups: FakePeerListLookups = FakePeerListLookups(),
    ) = buildConversationRow(conversation, noFlags, direct, wifiAware, lookups)

    private fun flags(
        conversation: ConversationSummary,
        lookups: FakePeerListLookups = FakePeerListLookups(),
        favoritePeers: Set<String> = emptySet(),
        peerFavoritedUs: Set<String> = emptySet(),
        peerFingerprints: Map<String, String> = emptyMap(),
        verifiedFingerprints: Set<String> = emptySet(),
    ) = conversationFavoriteFlags(
        conversation = conversation,
        favoriteRelationship =
            conversationFavoriteRelationship(conversation.identityAliases, lookups),
        favoritePeers = favoritePeers,
        peerFavoritedUs = peerFavoritedUs,
        peerFingerprints = peerFingerprints,
        verifiedFingerprints = verifiedFingerprints,
        lookups = lookups,
    )

    // P6 / P7

    @Test
    fun query_matchesNamePreviewOrDraft_trimmedAndCaseInsensitive() {
        val byName = conversation("a", displayName = "Alice")
        val byPreview = conversation("b", displayName = "bob", preview = "see you at NOON")
        val byDraft = conversation("c", displayName = "carl", preview = "", draft = "noon?")
        val other = conversation("d", displayName = "dan", preview = "bye")
        val all = listOf(byName, byPreview, byDraft, other)

        assertEquals(listOf(byPreview, byDraft), filterConversations(all, "  Noon "))
        assertEquals(listOf(byName), filterConversations(all, "ALI"))
        assertEquals(all, filterConversations(all, "   "))
    }

    @Test
    fun onlineAndOffline_splitByConnected_keepingDirectoryOrder() {
        val a = conversation("a", isConnected = true)
        val b = conversation("b")
        val c = conversation("c", isConnected = true)
        val d = conversation("d")

        assertEquals(listOf(a, c), onlineConversations(listOf(a, b, c, d)))
        assertEquals(listOf(b, d), offlineConversations(listOf(a, b, c, d)))
    }

    // P9 / Q-P7

    @Test
    fun searchField_fromEightConversations_countingUnfiltered() {
        assertFalse(showConversationSearch(List(7) { conversation("c$it") }))
        assertTrue(showConversationSearch(List(8) { conversation("c$it") }))
    }

    @Test
    fun status_precedence_loadingErrorEmptyNoResults() {
        val one = listOf(conversation("a"))
        val error = ConversationStoreState.Error("synthetic")

        assertEquals(
            ConversationStatusKind.Loading,
            conversationSectionStatus(ConversationStoreState.Loading, emptyList(), emptyList())
        )
        assertEquals(
            ConversationStatusKind.Error,
            conversationSectionStatus(error, emptyList(), emptyList())
        )
        assertEquals(
            ConversationStatusKind.Empty,
            conversationSectionStatus(ConversationStoreState.Ready, emptyList(), emptyList())
        )
        // Loading or Error with conversations falls through to the filter result.
        assertEquals(
            ConversationStatusKind.NoResults,
            conversationSectionStatus(ConversationStoreState.Loading, one, emptyList())
        )
        assertEquals(
            ConversationStatusKind.NoResults,
            conversationSectionStatus(error, one, emptyList())
        )
        assertNull(conversationSectionStatus(ConversationStoreState.Ready, one, one))
    }

    // P10

    @Test
    fun channelRows_selectedAndUnread_inSetOrder() {
        val rows = buildChannelRows(
            joinedChannels = linkedSetOf("#beta", "#alpha", "@bob"),
            currentChannel = "#alpha",
            unreadChannelMessages = mapOf("#beta" to 3, "#alpha" to 2)
        )

        assertEquals(
            listOf(
                ChannelRowUi("#beta", isSelected = false, unread = 3),
                ChannelRowUi("#alpha", isSelected = true, unread = 2),
                ChannelRowUi("@bob", isSelected = false, unread = 0),
            ),
            rows
        )
    }

    // P23

    @Test
    fun fingerprint_fromConnectedPeerFirst() {
        val c = conversation("contact_${"0".repeat(64)}", connectedPeerID = p1, aliases = setOf(p2))

        val result = flags(
            c,
            favoritePeers = setOf("fp-connected"),
            peerFingerprints = mapOf(p1 to "fp-connected", p2 to "fp-alias"),
            verifiedFingerprints = setOf("fp-connected"),
        )

        assertEquals(ConversationFavoriteFlags(true, false, true), result)
    }

    @Test
    fun fingerprint_thenFirstAliasWithOne() {
        val c = conversation("x", aliases = linkedSetOf("nostr_aaaa", p2))

        val result = flags(
            c,
            peerFavoritedUs = setOf("fp-alias"),
            peerFingerprints = mapOf(p2 to "fp-alias"),
        )

        assertEquals(ConversationFavoriteFlags(false, true, false), result)
    }

    @Test
    fun fingerprint_thenContactConversationId() {
        val fingerprint = "ab".repeat(32)
        val c = conversation("contact_$fingerprint")

        val result = flags(c, favoritePeers = setOf(fingerprint))

        assertTrue(result.isFavorite)
    }

    @Test
    fun fingerprint_thenStoredRelationshipNoiseKey_andItsTheyFavoritedUs() {
        val key = syntheticNoiseKey(0x11)
        val relationship = favoriteRelationship(key, nickname = "alice", theyFavoritedUs = true)
        val lookups = FakePeerListLookups(favoriteStatuses = mapOf(p2 to relationship))
        val c = conversation("x", aliases = linkedSetOf(p1, p2))

        val result = flags(
            c,
            lookups = lookups,
            verifiedFingerprints = setOf(ContactIdentityResolver.fingerprintHex(key)),
        )

        assertEquals(ConversationFavoriteFlags(false, true, true), result)
    }

    @Test
    fun noFingerprint_asksLookupForConnectedPeerElseConversation() {
        val lookups = FakePeerListLookups(favorites = setOf(p1))

        assertTrue(flags(conversation("x", connectedPeerID = p1), lookups = lookups).isFavorite)
        assertFalse(flags(conversation("y"), lookups = lookups).isFavorite)
        assertEquals(listOf(p1, "y"), lookups.isFavoriteCalls)
    }

    @Test
    fun relationshipLookupFailure_isSkipped() {
        val lookups = FakePeerListLookups(favoriteStatusFailure = true)

        assertNull(conversationFavoriteRelationship(setOf(p1, p2), lookups))
    }

    // P24 preview

    @Test
    fun preview_kinds() {
        assertEquals(
            ConversationPreviewKind.Draft("later"),
            row(conversation("a", draft = "later", type = BitchatMessageType.Image)).previewKind
        )
        assertEquals(
            ConversationPreviewKind.Image,
            row(conversation("a", type = BitchatMessageType.Image, draft = " ")).previewKind
        )
        assertEquals(
            ConversationPreviewKind.Voice,
            row(conversation("a", type = BitchatMessageType.Audio)).previewKind
        )
        assertEquals(
            ConversationPreviewKind.Literal("📎 notes.txt"),
            row(conversation("a", type = BitchatMessageType.File, preview = "notes.txt")).previewKind
        )
        assertEquals(
            ConversationPreviewKind.File,
            row(conversation("a", type = BitchatMessageType.File, preview = " ")).previewKind
        )
        assertEquals(
            ConversationPreviewKind.Literal("…"),
            row(conversation("a", preview = "")).previewKind
        )
        assertEquals(
            ConversationPreviewKind.Literal("hi"),
            row(conversation("a", preview = "hi")).previewKind
        )
    }

    @Test
    fun preview_youPrefixOnlyForOutgoingWithoutDraft() {
        val outgoing = row(conversation("a", outgoing = true))
        val outgoingWithDraft = row(conversation("a", outgoing = true, draft = "x"))
        val incoming = row(conversation("a"))

        assertTrue(outgoing.previewIsOutgoing)
        assertFalse(outgoing.hasDraft)
        assertFalse(outgoingWithDraft.previewIsOutgoing)
        assertTrue(outgoingWithDraft.hasDraft)
        assertFalse(incoming.previewIsOutgoing)
    }

    // P24 presence and connection

    @Test
    fun presence_connectedThenNostrThenNotInMesh() {
        assertEquals(
            ConversationPresence.Connected,
            row(conversation("a", isConnected = true, transport = DirectMessageTransport.NOSTR)).presence
        )
        assertEquals(
            ConversationPresence.ReachableViaNostr,
            row(conversation("a", transport = DirectMessageTransport.NOSTR)).presence
        )
        assertEquals(ConversationPresence.NotInMesh, row(conversation("a")).presence)
    }

    @Test
    fun connection_wifiAwareThenDirectThenRouted() {
        val c = conversation("a", connectedPeerID = p1.uppercase(), aliases = setOf("a"))

        val wifi = row(c, direct = setOf(p1), wifiAware = setOf(p1))
        val direct = row(c, direct = setOf(p1))
        val viaPeerInfo = row(
            c,
            lookups = FakePeerListLookups(
                peerInfos = mapOf(p1.uppercase() to meshPeerInfo(p1, isDirect = true))
            )
        )
        val routed = row(c)

        assertEquals(MeshConnectionKind.WifiAware, wifi.connection)
        assertTrue(wifi.isWifiAware && wifi.isDirect)
        assertEquals(MeshConnectionKind.Bluetooth, direct.connection)
        assertEquals(MeshConnectionKind.Bluetooth, viaPeerInfo.connection)
        assertEquals(MeshConnectionKind.Routed, routed.connection)
        assertFalse(routed.isDirect || routed.isWifiAware)
    }

    // P24 identity

    @Test
    fun colourKey_nostrPubkeyElseConversationId_andNameSplit() {
        val nostr = row(conversation("a", nostrPubkey = "AB".repeat(32), displayName = "alice#0a1b"))
        val mesh = row(conversation(p1, displayName = "bob"))

        assertEquals(PeerIdentity.nostr("AB".repeat(32)).stableKey, nostr.colorKey)
        assertEquals("alice", nostr.baseName)
        assertEquals("#0a1b", nostr.suffix)
        assertEquals(PeerIdentity.mesh(p1).stableKey, mesh.colorKey)
        assertEquals("", mesh.suffix)
        assertEquals(now, mesh.latestMessageAt)
    }

    @Test
    fun buildConversationRows_combinesSections() {
        val online = conversation("a", isConnected = true, connectedPeerID = p1)
        val offline = conversation("b", displayName = "bob", unreadCount = 2)

        val ui = buildConversationRows(
            conversations = listOf(online, offline),
            query = "",
            conversationStoreState = ConversationStoreState.Ready,
            favoritePeers = setOf("fp1"),
            peerFavoritedUs = emptySet(),
            peerFingerprints = mapOf(p1 to "fp1"),
            verifiedFingerprints = emptySet(),
            peerDirect = mapOf(p1.uppercase() to true, p2 to false),
            wifiAwarePeerIDs = emptySet(),
            lookups = FakePeerListLookups(),
        )

        assertFalse(ui.showSearch)
        assertNull(ui.status)
        assertEquals(listOf("a"), ui.online.map { it.conversationID })
        assertTrue(ui.online.single().isFavorite)
        assertEquals(MeshConnectionKind.Bluetooth, ui.online.single().connection)
        assertEquals(listOf("b"), ui.offline.map { it.conversationID })
        assertEquals(2, ui.offline.single().unreadCount)
    }

    @Test
    fun directAndWifiAwareIdentityIDs_areLowercased() {
        assertEquals(
            setOf(p1),
            directPeerIdentityIDs(mapOf(p1.uppercase() to true, p2 to false))
        )
        assertEquals(setOf(p1), wifiAwareIdentityIDs(setOf(p1.uppercase())))
    }
}
