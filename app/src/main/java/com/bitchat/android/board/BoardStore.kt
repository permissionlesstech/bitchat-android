package com.bitchat.android.board

import android.content.Context
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Base64

enum class BoardIngestResult {
    ACCEPTED,
    DUPLICATE,
    REJECTED
}

enum class BoardIngestSource {
    REMOTE,
    LOCAL,
    RESTORE
}

class BoardStore(
    private val file: File? = null,
    private val nowMs: () -> ULong = { System.currentTimeMillis().coerceAtLeast(0).toULong() }
) {
    object Limits {
        const val MAX_POSTS = 200
        const val MAX_POSTS_PER_AUTHOR = 5
        const val MAX_ORPHAN_TOMBSTONES = 100
        const val MAX_ORPHAN_TOMBSTONES_PER_AUTHOR = 5
        const val CLOCK_SKEW_MS: ULong = 3_600_000uL
        const val ORPHAN_TOMBSTONE_LIFETIME_MS: ULong = BoardWireConstants.MAX_LIFETIME_MS
    }

    private data class StoredPost(
        val post: BoardPostPacket,
        val packet: BitchatPacket,
        val rawPacket: ByteArray
    )

    private data class StoredTombstone(
        val tombstone: BoardTombstonePacket,
        val packet: BitchatPacket,
        val rawPacket: ByteArray,
        val retainUntil: ULong,
        val isOrphan: Boolean
    )

    private data class PersistedEntry(
        val packet: String,
        val retainUntil: String?
    )

    private val lock = Any()
    private val posts = mutableListOf<StoredPost>()
    private val tombstones = mutableListOf<StoredTombstone>()
    private val _postsSnapshot = MutableStateFlow<List<BoardPostPacket>>(emptyList())
    private val _postArrivals = MutableSharedFlow<BoardPostPacket>(extraBufferCapacity = 64)

    val postsSnapshot: StateFlow<List<BoardPostPacket>> = _postsSnapshot.asStateFlow()
    val postArrivals: SharedFlow<BoardPostPacket> = _postArrivals.asSharedFlow()

    init {
        loadFromDisk()
    }

    fun ingest(
        wire: BoardWire,
        packet: BitchatPacket,
        source: BoardIngestSource = BoardIngestSource.REMOTE
    ): BoardIngestResult {
        if (packet.type != MessageType.BOARD_POST.value || !wire.verifySignature()) {
            return BoardIngestResult.REJECTED
        }
        val rawPacket = packet.toBinaryData(padding = false) ?: return BoardIngestResult.REJECTED
        val now = nowMs()
        var arrival: BoardPostPacket? = null
        val result = synchronized(lock) {
            val outcome = ingestLocked(
                wire = wire,
                packet = packet,
                rawPacket = rawPacket,
                now = now,
                retainUntilOverride = null
            )
            if (outcome == BoardIngestResult.ACCEPTED && source != BoardIngestSource.RESTORE) {
                persistLocked()
            }
            if (outcome == BoardIngestResult.ACCEPTED &&
                source == BoardIngestSource.REMOTE &&
                wire is BoardWire.Post
            ) {
                arrival = wire.packet
            }
            outcome
        }
        arrival?.let(_postArrivals::tryEmit)
        return result
    }

    /**
     * Ingests a packet received from the mesh and reports whether this exact
     * arrival may continue through the live relay path.
     *
     * A duplicate board payload must not relay again: the payload signature
     * does not authenticate mutable outer packet fields such as timestamp, so
     * accepting duplicates for relay would let one captured notice be wrapped
     * in infinitely many distinct outer packets.
     */
    fun ingestRemoteForRelay(wire: BoardWire, packet: BitchatPacket): Boolean =
        ingest(wire, packet, BoardIngestSource.REMOTE) == BoardIngestResult.ACCEPTED

    fun posts(forGeohash: String): List<BoardPostPacket> = synchronized(lock) {
        pruneExpiredLocked(nowMs())
        posts.asSequence()
            .map { it.post }
            .filter { it.geohash == forGeohash }
            .sortedWith(
                compareByDescending<BoardPostPacket> { it.isUrgent }
                    .thenByDescending { it.createdAt }
            )
            .toList()
    }

    fun syncCandidates(): List<BitchatPacket> = synchronized(lock) {
        pruneExpiredLocked(nowMs())
        posts.map { it.packet } + tombstones.map { it.packet }
    }

    fun pruneExpired() = synchronized(lock) {
        val changed = pruneExpiredLocked(nowMs())
        if (changed) persistLocked()
    }

    fun wipe() = synchronized(lock) {
        posts.clear()
        tombstones.clear()
        file?.let { runCatching { if (it.exists()) it.delete() } }
        publishSnapshotLocked()
    }

    private fun ingestLocked(
        wire: BoardWire,
        packet: BitchatPacket,
        rawPacket: ByteArray,
        now: ULong,
        retainUntilOverride: ULong?
    ): BoardIngestResult {
        pruneExpiredLocked(now)
        return when (wire) {
            is BoardWire.Post -> ingestPostLocked(wire.packet, packet, rawPacket, now)
            is BoardWire.Tombstone -> ingestTombstoneLocked(
                wire.packet,
                packet,
                rawPacket,
                now,
                retainUntilOverride
            )
        }
    }

    private fun ingestPostLocked(
        post: BoardPostPacket,
        packet: BitchatPacket,
        rawPacket: ByteArray,
        now: ULong
    ): BoardIngestResult {
        if (post.expiresAt <= now) return BoardIngestResult.REJECTED
        if (post.createdAt > now.saturatedAdd(Limits.CLOCK_SKEW_MS)) {
            return BoardIngestResult.REJECTED
        }
        if (post.expiresAt > now.saturatedAdd(BoardWireConstants.MAX_LIFETIME_MS)
                .saturatedAdd(Limits.CLOCK_SKEW_MS)
        ) {
            return BoardIngestResult.REJECTED
        }
        if (tombstones.any {
                it.tombstone.postID.contentEquals(post.postID) &&
                    it.tombstone.authorSigningKey.contentEquals(post.authorSigningKey)
            }
        ) {
            return BoardIngestResult.REJECTED
        }
        if (posts.any { it.post.postID.contentEquals(post.postID) }) {
            return BoardIngestResult.DUPLICATE
        }

        posts += StoredPost(post, packet, rawPacket)
        enforcePostCapsLocked(post.authorSigningKey)
        publishSnapshotLocked()
        return BoardIngestResult.ACCEPTED
    }

    private fun ingestTombstoneLocked(
        tombstone: BoardTombstonePacket,
        packet: BitchatPacket,
        rawPacket: ByteArray,
        now: ULong,
        retainUntilOverride: ULong?
    ): BoardIngestResult {
        if (tombstones.any {
                it.tombstone.postID.contentEquals(tombstone.postID) &&
                    it.tombstone.authorSigningKey.contentEquals(tombstone.authorSigningKey)
            }
        ) {
            return BoardIngestResult.DUPLICATE
        }

        val maxRetain = minOf(
            tombstone.deletedAt.saturatedAdd(Limits.ORPHAN_TOMBSTONE_LIFETIME_MS),
            now.saturatedAdd(Limits.ORPHAN_TOMBSTONE_LIFETIME_MS)
                .saturatedAdd(Limits.CLOCK_SKEW_MS)
        )
        val matchingPostIndex = posts.indexOfFirst { it.post.postID.contentEquals(tombstone.postID) }
        val retainUntil: ULong
        val isOrphan: Boolean
        if (matchingPostIndex >= 0) {
            val target = posts[matchingPostIndex].post
            if (!target.authorSigningKey.contentEquals(tombstone.authorSigningKey)) {
                return BoardIngestResult.REJECTED
            }
            retainUntil = target.expiresAt
            isOrphan = false
            posts.removeAt(matchingPostIndex)
            publishSnapshotLocked()
        } else if (retainUntilOverride != null) {
            retainUntil = minOf(retainUntilOverride, maxRetain)
            isOrphan = false
        } else {
            retainUntil = maxRetain
            isOrphan = true
        }
        if (retainUntil <= now) return BoardIngestResult.REJECTED

        tombstones += StoredTombstone(
            tombstone = tombstone,
            packet = packet,
            rawPacket = rawPacket,
            retainUntil = retainUntil,
            isOrphan = isOrphan
        )
        if (isOrphan) enforceOrphanTombstoneCapsLocked(tombstone.authorSigningKey)
        return BoardIngestResult.ACCEPTED
    }

    private fun enforcePostCapsLocked(author: ByteArray) {
        val authorPosts = posts.filter { it.post.authorSigningKey.contentEquals(author) }
        evictOldestPostsLocked(authorPosts, Limits.MAX_POSTS_PER_AUTHOR)
        evictOldestPostsLocked(posts.toList(), Limits.MAX_POSTS)
    }

    private fun evictOldestPostsLocked(candidates: List<StoredPost>, keep: Int) {
        val victimIDs = candidates.sortedBy { it.post.createdAt }
            .take((candidates.size - keep).coerceAtLeast(0))
            .map { it.post.postID.toHex() }
            .toSet()
        if (victimIDs.isNotEmpty()) {
            posts.removeAll { it.post.postID.toHex() in victimIDs }
        }
    }

    private fun enforceOrphanTombstoneCapsLocked(author: ByteArray) {
        val authorOrphans = tombstones.filter {
            it.isOrphan && it.tombstone.authorSigningKey.contentEquals(author)
        }
        removeOldestTombstonesLocked(
            authorOrphans,
            authorOrphans.size - Limits.MAX_ORPHAN_TOMBSTONES_PER_AUTHOR
        )
        val allOrphans = tombstones.filter { it.isOrphan }
        removeOldestTombstonesLocked(
            allOrphans,
            allOrphans.size - Limits.MAX_ORPHAN_TOMBSTONES
        )
    }

    private fun removeOldestTombstonesLocked(
        candidates: List<StoredTombstone>,
        count: Int
    ) {
        if (count <= 0) return
        val victims = candidates.take(count)
        tombstones.removeAll { stored -> victims.any { it === stored } }
    }

    private fun pruneExpiredLocked(now: ULong): Boolean {
        val postsBefore = posts.size
        val tombstonesBefore = tombstones.size
        posts.removeAll { it.post.expiresAt <= now }
        tombstones.removeAll { it.retainUntil <= now }
        if (posts.size != postsBefore) publishSnapshotLocked()
        return posts.size != postsBefore || tombstones.size != tombstonesBefore
    }

    private fun publishSnapshotLocked() {
        _postsSnapshot.value = posts.map { it.post }
    }

    private fun persistLocked() {
        val target = file ?: return
        val entries = posts.map {
            PersistedEntry(
                packet = Base64.getEncoder().encodeToString(it.rawPacket),
                retainUntil = null
            )
        } + tombstones.map {
            PersistedEntry(
                packet = Base64.getEncoder().encodeToString(it.rawPacket),
                retainUntil = it.retainUntil.toString()
            )
        }
        runCatching {
            if (entries.isEmpty()) {
                if (target.exists()) target.delete()
                return
            }
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(Gson().toJson(entries))
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }.onFailure {
            Log.e(TAG, "Failed to persist board store: ${it.message}")
        }
    }

    private fun loadFromDisk() {
        val target = file ?: return
        if (!target.isFile) return
        val entries = runCatching {
            val type = object : TypeToken<List<PersistedEntry>>() {}.type
            Gson().fromJson<List<PersistedEntry>>(target.readText(), type)
        }.getOrNull() ?: return
        val now = nowMs()
        synchronized(lock) {
            for (entry in entries) {
                val raw = runCatching { Base64.getDecoder().decode(entry.packet) }.getOrNull() ?: continue
                val packet = BitchatPacket.fromBinaryData(raw) ?: continue
                if (packet.type != MessageType.BOARD_POST.value) continue
                val wire = BoardWireCodec.decode(packet.payload) ?: continue
                if (!wire.verifySignature()) continue
                ingestLocked(
                    wire = wire,
                    packet = packet,
                    rawPacket = raw,
                    now = now,
                    retainUntilOverride = entry.retainUntil?.toULongOrNull()
                )
            }
            publishSnapshotLocked()
        }
    }

    companion object {
        private const val TAG = "BoardStore"

        @Volatile
        private var instance: BoardStore? = null

        fun getInstance(context: Context): BoardStore =
            instance ?: synchronized(this) {
                instance ?: BoardStore(
                    File(context.applicationContext.filesDir, "board/posts.json")
                ).also { instance = it }
            }
    }
}

private fun ULong.saturatedAdd(other: ULong): ULong =
    if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
