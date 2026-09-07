package com.bitchat.android.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.media.mediaTransferProgress
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class ConversationUiParityTest {
    @Test fun `private media follows mesh route availability regardless of public timeline`() {
        for (nostr in listOf(false, true)) {
            val context = ConversationUiContext("private", "synthetic-peer", nostr)
            assertFalse(context.supportsMediaSend(false))
            assertTrue(context.supportsMediaSend(true))
        }
        assertTrue(ConversationUiContext("mesh").supportsMediaSend(false))
        assertFalse(ConversationUiContext("nostr", isNostr = true).supportsMediaSend(true))
    }

    @Test fun `mentions use the visible conversation and keep the cursor at the end`() {
        val mesh = appendConversationMention(TextFieldValue("hello"), "alice#1234", ConversationUiContext("mesh"))
        assertEquals("hello @alice ", mesh.text)
        assertEquals(mesh.text.length, mesh.selection.start)
        val nostr = appendConversationMention(TextFieldValue("hello "), "alice#1234", ConversationUiContext("dm", "peer", true))
        assertEquals("hello @alice#1234 ", nostr.text)
    }

    @Test fun `renamed sender retains transfer controls through peer identity`() {
        val message = BitchatMessage(
            sender = "old-name", senderPeerID = "synthetic-self", content = "fixture",
            timestamp = Date(0), isPrivate = true,
            deliveryStatus = DeliveryStatus.PartiallyDelivered(1, 4),
        )
        assertEquals(0.25f, mediaTransferProgress(message, message.isFromSelf("new-name", "synthetic-self")))
        assertNull(mediaTransferProgress(message, false))
        assertNull(mediaTransferProgress(message.copy(deliveryStatus = DeliveryStatus.PartiallyDelivered(4, 4)), true))
        assertNull(mediaTransferProgress(message.copy(deliveryStatus = DeliveryStatus.PartiallyDelivered(0, 0)), true))
    }
}
