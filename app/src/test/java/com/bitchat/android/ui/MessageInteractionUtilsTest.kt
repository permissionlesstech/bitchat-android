package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class MessageInteractionUtilsTest {

    @Test
    fun isFromSelf_doesNotMatchOtherPeerWhenNicknameCollides() {
        val otherPeerMessage = BitchatMessage(
            sender = "alice",
            content = "hello",
            timestamp = Date(),
            senderPeerID = "peer-other"
        )
        assertFalse(otherPeerMessage.isFromSelf(currentUserNickname = "alice", myPeerId = "peer-me"))
    }

    @Test
    fun isFromSelf_matchesOwnPeerIdEvenAfterRename() {
        val myMessage = BitchatMessage(
            sender = "old-nick",
            content = "hello",
            timestamp = Date(),
            senderPeerID = "peer-me"
        )
        assertTrue(myMessage.isFromSelf(currentUserNickname = "new-nick", myPeerId = "peer-me"))
    }

    @Test
    fun isFromSelf_fallsBackToNicknameWhenPeerIdMissing() {
        val legacyMessage = BitchatMessage(
            sender = "alice#1234",
            content = "hello",
            timestamp = Date(),
            senderPeerID = null
        )
        assertTrue(legacyMessage.isFromSelf(currentUserNickname = "alice", myPeerId = "peer-me"))
    }
}
