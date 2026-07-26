package com.bitchat.android.board

import com.bitchat.android.nostr.LocationNotesManager
import kotlin.math.abs

enum class NoticeSource {
    MESH,
    NOSTR
}

data class UnifiedNotice(
    val id: String,
    val content: String,
    val nickname: String,
    val createdAtMs: Long,
    val geohash: String,
    val urgent: Boolean,
    val expiresAtMs: Long?,
    val source: NoticeSource,
    val boardPost: BoardPostPacket? = null,
    val nostrNote: LocationNotesManager.Note? = null
)

object UnifiedNotices {
    private const val DEDUPLICATION_WINDOW_MS = 15 * 60 * 1_000L

    /**
     * Combines exact-scope mesh board posts with relay notes. When a relay
     * bridge copy matches a board post, the signed board copy is authoritative.
     */
    fun merge(
        geohash: String,
        boardPosts: List<BoardPostPacket>,
        relayNotes: List<LocationNotesManager.Note>
    ): List<UnifiedNotice> {
        val normalized = geohash.lowercase()
        val scopedPosts = boardPosts.filter { it.geohash == normalized }
        val boardNotices = scopedPosts.map { post ->
            UnifiedNotice(
                id = "mesh:${post.postID.toHex()}",
                content = post.content,
                nickname = post.authorNickname,
                createdAtMs = post.createdAt.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
                geohash = post.geohash,
                urgent = post.isUrgent,
                expiresAtMs = post.expiresAt.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
                source = NoticeSource.MESH,
                boardPost = post
            )
        }
        val relayNotices = relayNotes.asSequence()
            .filterNot { note ->
                scopedPosts.any { post ->
                    post.geohash == note.geohash.lowercase() &&
                    post.content == note.content &&
                        post.authorNickname.ifBlank { "anon" } ==
                            note.nickname?.trim()?.takeIf { it.isNotEmpty() }.orEmpty()
                                .ifEmpty { "anon" } &&
                        abs(
                            post.createdAt.coerceAtMost(Long.MAX_VALUE.toULong()).toLong() -
                                note.createdAt.toLong() * 1_000L
                        ) <= DEDUPLICATION_WINDOW_MS
                }
            }
            .map { note ->
                UnifiedNotice(
                    id = "nostr:${note.id}",
                    content = note.content,
                    nickname = note.nickname.orEmpty(),
                    createdAtMs = note.createdAt.toLong() * 1_000L,
                    geohash = note.geohash.lowercase(),
                    urgent = note.isUrgent,
                    expiresAtMs = note.expiresAt?.toLong()?.times(1_000L),
                    source = NoticeSource.NOSTR,
                    nostrNote = note
                )
            }
            .toList()

        return (boardNotices + relayNotices).sortedWith(
            compareByDescending<UnifiedNotice> { it.urgent }
                .thenByDescending { it.createdAtMs }
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
