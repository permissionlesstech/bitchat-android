package com.bitchat.android.board

import com.bitchat.android.nostr.LocationNotesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedNoticesTest {
    private val baseSeconds = 1_700_000_000
    private val baseMs = baseSeconds.toULong() * 1_000uL

    @Test
    fun `bridged relay copy is deduplicated in favor of board post`() {
        val post = post(content = "water at the gate")
        val relayCopy = note(content = "water at the gate", createdAt = baseSeconds + 30)

        val result = UnifiedNotices.merge("u33dc", listOf(post), listOf(relayCopy))

        assertEquals(1, result.size)
        assertEquals(NoticeSource.MESH, result.single().source)
    }

    @Test
    fun `same content from neighbor cell is retained`() {
        val post = post(content = "free tent")
        val neighbor = note(content = "free tent", geohash = "u33dd")

        val result = UnifiedNotices.merge("u33dc", listOf(post), listOf(neighbor))

        assertEquals(2, result.size)
        assertTrue(result.any { it.source == NoticeSource.NOSTR })
    }

    @Test
    fun `different author and out of window notes remain`() {
        val post = post(content = "meet at six")
        val otherAuthor = note(content = "meet at six", nickname = "bob")
        val oldCopy = note(
            content = "meet at six",
            createdAt = baseSeconds - 16 * 60
        )

        val result = UnifiedNotices.merge(
            "u33dc",
            listOf(post),
            listOf(otherAuthor, oldCopy)
        )

        assertEquals(3, result.size)
    }

    @Test
    fun `anonymous copies deduplicate and urgent sorts first`() {
        val anonymous = post(content = "hello", nickname = "")
        val bridged = note(content = "hello", nickname = null)
        val urgentRelay = note(
            content = "road closed",
            nickname = "carol",
            createdAt = baseSeconds - 60,
            urgent = true
        )

        val result = UnifiedNotices.merge(
            "u33dc",
            listOf(anonymous),
            listOf(bridged, urgentRelay)
        )

        assertEquals(listOf("road closed", "hello"), result.map { it.content })
        assertTrue(result.first().urgent)
        assertFalse(result.last().urgent)
    }

    private fun post(
        content: String,
        nickname: String = "alice",
        createdAt: ULong = baseMs,
        urgent: Boolean = false
    ) = BoardPostPacket(
        postID = ByteArray(16) { content.hashCode().toByte() },
        geohash = "u33dc",
        content = content,
        authorSigningKey = ByteArray(32) { 1 },
        authorNickname = nickname,
        createdAt = createdAt,
        expiresAt = createdAt + 86_400_000uL,
        flags = if (urgent) BoardPostPacket.URGENT_FLAG else 0u,
        signature = ByteArray(64) { 2 }
    )

    private fun note(
        content: String,
        nickname: String? = "alice",
        createdAt: Int = baseSeconds,
        geohash: String = "u33dc",
        urgent: Boolean = false
    ) = LocationNotesManager.Note(
        id = "$content-$nickname-$createdAt-$geohash",
        pubkey = "deadbeef",
        content = content,
        createdAt = createdAt,
        nickname = nickname,
        geohash = geohash,
        isUrgent = urgent
    )
}
