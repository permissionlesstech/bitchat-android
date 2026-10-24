package com.bitchat.android.testhook

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class ArrivedMessageMatcherTest {

    private val receiverClockAtStart = 1_700_000_000_000L

    private fun message(id: String, content: String, timestampMs: Long) = BitchatMessage(
        id = id,
        sender = "peer",
        content = content,
        timestamp = Date(timestampMs),
        senderPeerID = "0102030405060708"
    )

    @Test
    fun messageStampedBySlowerSenderClockStillCountsAsArrived() {
        val matcher = ArrivedMessageMatcher(emptyList()) { it.content.contains("token") }
        val behindReceiver = message("new", "hello token", receiverClockAtStart - 140_000)

        assertEquals(behindReceiver, matcher.firstArrived(listOf(behindReceiver)))
    }

    @Test
    fun messagePresentAtStartIsIgnoredEvenWhenSenderClockIsAhead() {
        val preexisting = message("old", "hello token", receiverClockAtStart + 140_000)
        val matcher = ArrivedMessageMatcher(listOf(preexisting)) { it.content.contains("token") }

        assertNull(matcher.firstArrived(listOf(preexisting)))
    }

    @Test
    fun arrivedMessageMustStillPassFilter() {
        val matcher = ArrivedMessageMatcher(emptyList()) { it.content.contains("token") }
        val unrelated = message("a", "other", receiverClockAtStart)
        val wanted = message("b", "hello token", receiverClockAtStart)

        assertEquals(wanted, matcher.firstArrived(listOf(unrelated, wanted)))
    }
}
