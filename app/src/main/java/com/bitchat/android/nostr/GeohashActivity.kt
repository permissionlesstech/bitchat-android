package com.bitchat.android.nostr

import com.bitchat.android.geohash.Geohash

/** Recent public activity in one exact geohash channel, as seen on the globe picker. */
data class GeohashChannelActivity(
    val geohash: String,
    val latitude: Double,
    val longitude: Double,
    /** Distinct people with a heartbeat or message inside the presence window. */
    val online: Int,
    /** Distinct people who wrote in the channel inside the chat window. */
    val chatters: Int,
    /** Chat messages inside the chat window. */
    val messages: Int,
    /** Distinct people across both windows; this is what a marker shows. */
    val people: Int,
    /** Newest event, epoch seconds. */
    val lastActiveAt: Long
)

data class GeohashActivitySnapshot(
    /** Channels ordered by [GeohashChannelActivity.people], busiest first. */
    val channels: List<GeohashChannelActivity>,
    /** Estimated people online worldwide; see [estimateDistinctPeople]. */
    val totalOnline: Int,
    /** Channels with at least one message inside the chat window. */
    val activeChats: Int
) {
    private val byGeohash: Map<String, GeohashChannelActivity> by lazy { channels.associateBy { it.geohash } }

    fun channel(geohash: String): GeohashChannelActivity? = byGeohash[geohash]

    companion object {
        val EMPTY = GeohashActivitySnapshot(emptyList(), 0, 0)
    }
}

/**
 * Estimates distinct people across [channels].
 *
 * Clients heartbeat into several nested channels at once (region, province and city), each under
 * a channel-specific identity, so summing double counts. Each person appears about once per
 * precision level, so the busiest level is the honest estimate.
 */
fun estimateDistinctPeople(
    channels: Collection<GeohashChannelActivity>,
    count: (GeohashChannelActivity) -> Int = { it.people }
): Int {
    if (channels.isEmpty()) return 0
    val perLevel = IntArray(13)
    channels.forEach { perLevel[it.geohash.length.coerceIn(0, 12)] += count(it) }
    return perLevel.max()
}

/**
 * Folds global kind-20000/20001 traffic into per-channel activity, the way bitmap.lat does:
 * chat messages count for an hour, heartbeats for five minutes.
 *
 * Geohash identities are derived per channel, so a person active in several channels is
 * counted once in each. Not thread-safe; the owner serializes access.
 */
class GeohashActivityAggregator(
    private val presenceWindowSeconds: Long = PRESENCE_WINDOW_SECONDS,
    private val chatWindowSeconds: Long = CHAT_WINDOW_SECONDS,
    private val maxChannels: Int = MAX_CHANNELS
) {
    private class Message(val pubkey: String, val createdAt: Long)

    private class Channel(val latitude: Double, val longitude: Double) {
        /** pubkey -> newest event of either kind. */
        val lastSeen = HashMap<String, Long>()
        /** event id -> message, for chat events only. */
        val messages = LinkedHashMap<String, Message>()
        var lastActiveAt = 0L
    }

    private val channels = HashMap<String, Channel>()

    /**
     * True when [record] would add a person or a message rather than refresh a timestamp.
     * The monitor verifies signatures only for these, so forged events cannot inflate counts.
     */
    fun wouldGrow(geohash: String, pubkey: String, isChat: Boolean, eventId: String): Boolean {
        val channel = channels[normalize(geohash) ?: return false] ?: return true
        return if (isChat) eventId !in channel.messages else pubkey.lowercase() !in channel.lastSeen
    }

    /** Records one event. Returns true when the aggregate changed. */
    fun record(
        kind: Int,
        eventId: String,
        pubkey: String,
        geohash: String,
        createdAt: Long,
        content: String,
        nowSeconds: Long
    ): Boolean {
        if (kind != NostrKind.EPHEMERAL_EVENT && kind != NostrKind.GEOHASH_PRESENCE) return false
        val cell = normalize(geohash) ?: return false
        if (createdAt > nowSeconds + MAX_CLOCK_SKEW_SECONDS) return false
        val isChat = kind == NostrKind.EPHEMERAL_EVENT && content.isNotBlank()
        val window = if (isChat) chatWindowSeconds else presenceWindowSeconds
        if (createdAt < nowSeconds - window) return false

        val channel = channels[cell] ?: run {
            val (lat, lon) = Geohash.decodeToCenter(cell)
            Channel(lat, lon).also { channels[cell] = it }
        }
        val key = pubkey.lowercase()
        var changed = false
        val previous = channel.lastSeen[key]
        if (previous == null || createdAt > previous) {
            channel.lastSeen[key] = createdAt
            changed = true
        }
        if (isChat && eventId !in channel.messages) {
            channel.messages[eventId] = Message(key, createdAt)
            if (channel.messages.size > MAX_MESSAGES_PER_CHANNEL) {
                channel.messages.remove(channel.messages.keys.first())
            }
            changed = true
        }
        if (createdAt > channel.lastActiveAt) channel.lastActiveAt = createdAt
        if (channels.size > maxChannels) evictStalest()
        return changed
    }

    /** Drops events that left their window. Returns true when anything was removed. */
    fun prune(nowSeconds: Long): Boolean {
        var changed = false
        val iterator = channels.values.iterator()
        while (iterator.hasNext()) {
            val channel = iterator.next()
            if (channel.messages.values.removeAll { it.createdAt < nowSeconds - chatWindowSeconds }) changed = true
            val chatters = channel.messages.values.mapTo(HashSet()) { it.pubkey }
            if (channel.lastSeen.entries.removeAll { (pubkey, seen) ->
                    seen < nowSeconds - presenceWindowSeconds && pubkey !in chatters
                }
            ) changed = true
            if (channel.lastSeen.isEmpty() && channel.messages.isEmpty()) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    fun snapshot(nowSeconds: Long): GeohashActivitySnapshot {
        val presenceCutoff = nowSeconds - presenceWindowSeconds
        val chatCutoff = nowSeconds - chatWindowSeconds
        val totalOnline: Int
        var activeChats = 0
        val result = ArrayList<GeohashChannelActivity>(channels.size)
        for ((geohash, channel) in channels) {
            val chatters = HashSet<String>()
            var messages = 0
            for (message in channel.messages.values) {
                if (message.createdAt < chatCutoff) continue
                chatters.add(message.pubkey)
                messages++
            }
            var online = 0
            val people = HashSet<String>(chatters)
            for ((pubkey, seen) in channel.lastSeen) {
                if (seen >= presenceCutoff) {
                    online++
                    people.add(pubkey)
                }
            }
            if (people.isEmpty()) continue
            if (messages > 0) activeChats++
            result.add(
                GeohashChannelActivity(
                    geohash = geohash,
                    latitude = channel.latitude,
                    longitude = channel.longitude,
                    online = online,
                    chatters = chatters.size,
                    messages = messages,
                    people = people.size,
                    lastActiveAt = channel.lastActiveAt
                )
            )
        }
        result.sortWith(compareByDescending<GeohashChannelActivity> { it.people }.thenByDescending { it.messages })
        totalOnline = estimateDistinctPeople(result) { it.online }
        return GeohashActivitySnapshot(result, totalOnline, activeChats)
    }

    fun clear() = channels.clear()

    private fun evictStalest() {
        val stalest = channels.minByOrNull { it.value.lastActiveAt }?.key ?: return
        channels.remove(stalest)
    }

    private fun normalize(geohash: String): String? {
        val cell = geohash.trim().lowercase()
        if (cell.isEmpty() || cell.length > MAX_GEOHASH_LENGTH) return null
        return cell.takeIf { value -> value.all { it in GEOHASH_ALPHABET } }
    }

    companion object {
        const val PRESENCE_WINDOW_SECONDS = 300L
        const val CHAT_WINDOW_SECONDS = 3_600L
        private const val MAX_CHANNELS = 600
        private const val MAX_MESSAGES_PER_CHANNEL = 500
        private const val MAX_CLOCK_SKEW_SECONDS = 300L
        private const val MAX_GEOHASH_LENGTH = 12
        private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
    }
}
