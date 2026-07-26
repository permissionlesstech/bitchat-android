package com.bitchat.android.ui

import android.os.Build
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateChatManagerTest {
    private lateinit var state: ChatState
    private lateinit var manager: PrivateChatManager

    @Before
    fun setUp() {
        AppStateStore.clear()
        state = ChatState(TestScope())
        manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = DataManager(RuntimeEnvironment.getApplication()),
            noiseSessionDelegate = mock()
        )
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun `nostr message is stored after sender alias canonicalizes to contact id`() {
        val conversationID =
            ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 7 })
        val message = BitchatMessage(
            id = "nostr-message",
            sender = "alice",
            content = "hello over nostr",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = conversationID
        )

        manager.handleIncomingPrivateMessage(
            message = message,
            suppressUnread = false,
            origin = PrivateMessageOrigin.NOSTR
        )

        assertEquals(listOf(message), state.getPrivateChatsValue()[conversationID])
    }

    @Test
    fun `canonical conversation sends read receipt through live mesh peer id`() {
        val noiseKey = ByteArray(32) { 9 }
        val meshPeerID = ContactIdentityResolver.peerIdForNoiseKey(noiseKey)
        val conversationID = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey)
        val message = BitchatMessage(
            id = "mesh-message",
            sender = "alice",
            content = "hello over mesh",
            timestamp = Date(1),
            isPrivate = true,
            senderPeerID = meshPeerID
        )
        val meshService = mock<MeshService>()
        val peerInfo = PeerInfo(
            id = meshPeerID,
            nickname = "alice",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = noiseKey,
            signingPublicKey = null,
            isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        )
        state.setNickname("bob")
        state.setPrivateChats(mapOf(conversationID to listOf(message)))
        whenever(meshService.getPeerInfo(meshPeerID)).thenReturn(peerInfo)
        whenever(meshService.hasEstablishedSession(meshPeerID)).thenReturn(true)

        manager.sendReadReceiptsForPeer(
            conversationID = conversationID,
            meshPeerID = meshPeerID,
            meshService = meshService
        )

        verify(meshService).sendReadReceipt(message.id, meshPeerID, "bob")
    }
}
