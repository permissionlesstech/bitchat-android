package com.bitchat.android.board

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.LocationNotesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Creates and removes signed board entries while keeping UI-only state out of
 * the transport layer.
 */
class BoardManager(
    private val store: BoardStore,
    private val scope: CoroutineScope,
    private val meshProvider: () -> MeshService,
    private val geoIdentityProvider: (String) -> BoardSigningIdentity? = { null },
    private val notesManager: LocationNotesManager = LocationNotesManager.getInstance(),
    private val nowMs: () -> ULong = { System.currentTimeMillis().coerceAtLeast(0).toULong() },
    private val random: SecureRandom = SecureRandom(),
    private val onUrgentPosts: (geohash: String, posts: List<BoardPostPacket>) -> Unit = { _, _ -> }
) {
    private val _unseenScopes = MutableStateFlow<Set<String>>(emptySet())
    private val bridgedEventIDs = mutableMapOf<String, String>()
    private val handledPostIDs = mutableSetOf<String>()
    private val pendingUrgent = mutableMapOf<String, MutableList<BoardPostPacket>>()
    private var alertFlushJob: Job? = null

    val posts: StateFlow<List<BoardPostPacket>> = store.postsSnapshot
    val unseenScopes: StateFlow<Set<String>> = _unseenScopes.asStateFlow()

    init {
        scope.launch {
            store.postArrivals.collect { post ->
                handleArrival(post)
            }
        }
    }

    fun posts(geohash: String): List<BoardPostPacket> = store.posts(geohash.lowercase())

    fun isOwnPost(post: BoardPostPacket): Boolean =
        signingIdentityFor(post.geohash)
            ?.publicKey
            ?.contentEquals(post.authorSigningKey) == true

    fun createPost(
        content: String,
        geohash: String,
        nickname: String?,
        urgent: Boolean,
        expiryDays: Int
    ): Boolean {
        val trimmed = content.trim()
        val contentBytes = trimmed.toByteArray(Charsets.UTF_8)
        val normalizedGeohash = geohash.lowercase()
        if (contentBytes.size !in 1..BoardWireConstants.CONTENT_MAX_BYTES ||
            expiryDays !in 1..7 ||
            !isValidGeohash(normalizedGeohash)
        ) {
            return false
        }

        val mesh = meshProvider()
        val identity = signingIdentityFor(normalizedGeohash) ?: return false
        val signingKey = identity.publicKey.copyOf()
        val postID = ByteArray(BoardWireConstants.POST_ID_LENGTH).also(random::nextBytes)
        val createdAt = nowMs()
        val expiresAt = createdAt + expiryDays.toULong() * DAY_MS
        val authorNickname = truncateUtf8(nickname.orEmpty(), BoardWireConstants.NICKNAME_MAX_BYTES)
        val flags: UByte = if (urgent) BoardPostPacket.URGENT_FLAG else 0u
        val signingBytes = BoardPostPacket.signingBytes(
            postID = postID,
            geohash = normalizedGeohash,
            content = trimmed,
            authorSigningKey = signingKey,
            authorNickname = authorNickname,
            createdAt = createdAt,
            expiresAt = expiresAt,
            flags = flags
        )
        val signature = identity.sign(signingBytes)
            ?.takeIf { it.size == BoardWireConstants.SIGNATURE_LENGTH }
            ?: return false
        val post = BoardPostPacket(
            postID = postID,
            geohash = normalizedGeohash,
            content = trimmed,
            authorSigningKey = signingKey,
            authorNickname = authorNickname,
            createdAt = createdAt,
            expiresAt = expiresAt,
            flags = flags,
            signature = signature
        )
        mesh.sendBoardPayload(BoardWireCodec.encode(BoardWire.Post(post)))

        if (normalizedGeohash.isNotEmpty()) {
            notesManager.publishBoardBridge(
                content = trimmed,
                geohash = normalizedGeohash,
                nickname = authorNickname,
                expiresAtSeconds = (expiresAt / 1_000u).coerceAtMost(Int.MAX_VALUE.toULong()).toInt(),
                urgent = urgent
            ) { eventID ->
                synchronized(bridgedEventIDs) {
                    bridgedEventIDs[post.identityKey()] = eventID
                }
            }
        }
        return true
    }

    fun deletePost(post: BoardPostPacket): Boolean {
        val identity = signingIdentityFor(post.geohash)
            ?.takeIf { it.publicKey.contentEquals(post.authorSigningKey) }
            ?: return false
        val deletedAt = nowMs()
        val signature = identity.sign(
            BoardTombstonePacket.signingBytes(post.postID, deletedAt)
        )?.takeIf { it.size == BoardWireConstants.SIGNATURE_LENGTH } ?: return false
        val tombstone = BoardTombstonePacket(
            postID = post.postID,
            authorSigningKey = post.authorSigningKey,
            deletedAt = deletedAt,
            signature = signature
        )
        meshProvider().sendBoardPayload(BoardWireCodec.encode(BoardWire.Tombstone(tombstone)))

        if (post.geohash.isNotEmpty()) {
            val eventID = synchronized(bridgedEventIDs) {
                bridgedEventIDs.remove(post.identityKey())
            }
            if (eventID != null) notesManager.deleteEvent(eventID, post.geohash)
        }
        return true
    }

    fun markSeen(scopes: Set<String>) {
        if (scopes.isEmpty()) return
        _unseenScopes.value = _unseenScopes.value - scopes
    }

    fun clearTransientState() {
        _unseenScopes.value = emptySet()
        synchronized(bridgedEventIDs) { bridgedEventIDs.clear() }
        handledPostIDs.clear()
        pendingUrgent.clear()
        alertFlushJob?.cancel()
        alertFlushJob = null
    }

    private fun handleArrival(post: BoardPostPacket) {
        if (!handledPostIDs.add(post.identityKey()) || isOwnPost(post)) return
        _unseenScopes.value = _unseenScopes.value + post.geohash
        val age = nowMs().toLong() -
            post.createdAt.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
        if (!post.isUrgent || age > URGENT_RECENCY_MS) return

        pendingUrgent.getOrPut(post.geohash) { mutableListOf() } += post
        if (alertFlushJob == null) {
            alertFlushJob = scope.launch {
                delay(ALERT_COLLAPSE_MS)
                val pending = pendingUrgent.mapValues { it.value.toList() }
                pendingUrgent.clear()
                alertFlushJob = null
                pending.forEach { (geohash, posts) -> onUrgentPosts(geohash, posts) }
            }
        }
    }

    private fun isValidGeohash(value: String): Boolean =
        value.isEmpty() ||
            (value.length <= BoardWireConstants.GEOHASH_MAX_LENGTH &&
                value.all { it in BoardWireConstants.GEOHASH_ALPHABET })

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        var result = value
        while (result.toByteArray(Charsets.UTF_8).size > maxBytes && result.isNotEmpty()) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun BoardPostPacket.identityKey(): String =
        "${authorSigningKey.toHex()}:${postID.toHex()}"

    private fun signingIdentityFor(geohash: String): BoardSigningIdentity? {
        if (geohash.isNotEmpty()) {
            // Never fall back to the stable mesh identity for a location scope.
            return runCatching { geoIdentityProvider(geohash) }.getOrNull()
        }
        val mesh = meshProvider()
        val publicKey = mesh.getSigningPublicKey()
            ?.takeIf { it.size == BoardWireConstants.SIGNING_KEY_LENGTH }
            ?: return null
        return BoardSigningIdentity(publicKey, mesh::signData)
    }

    private companion object {
        const val DAY_MS: ULong = 86_400_000uL
        const val URGENT_RECENCY_MS = 30 * 60 * 1_000L
        const val ALERT_COLLAPSE_MS = 4_000L
    }
}
