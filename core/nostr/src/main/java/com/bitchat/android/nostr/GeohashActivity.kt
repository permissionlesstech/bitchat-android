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
 * The one rule for "who is in a geohash channel", shared by the channel sheet and the globe:
 * anyone with a heartbeat or message in the last five minutes, plus anyone who wrote in the
 * last hour. Chat messages are counted for the hour as well.
 *
 * Geohash identities are derived per channel, so a person active in several channels is
 * counted once in each. Timestamps are milliseconds; future timestamps are clamped to now so a
 * skewed clock cannot pin anyone online. Not thread-safe; the owner serializes access.
 */
class GeohashActivityAggregator(
    private val presenceWindowMillis: Long = PRESENCE_WINDOW_SECONDS * 1_000,
    private val chatWindowMillis: Long = CHAT_WINDOW_SECONDS * 1_000,
    private val maxChannels: Int = MAX_CHANNELS
) {
    private class Message(val pubkey: String, val createdAt: Long)

    private class Channel(val latitude: Double, val longitude: Double) {
        /** pubkey -> newest activity of any kind. */
        val lastSeen = HashMap<String, Long>()
        /** pubkey -> newest chat message, kept apart from [messages] so the cap never drops a writer. */
        val lastChat = HashMap<String, Long>()
        /** event id -> message, for chat events only; capped, so it bounds the message count only. */
        val messages = LinkedHashMap<String, Message>()
        var lastActiveAt = 0L
    }

    private val channels = HashMap<String, Channel>()

    val geohashes: Set<String> get() = channels.keys

    /** Records one kind-20000/20001 event. Returns true when the aggregate changed. */
    fun record(
        kind: Int,
        eventId: String,
        pubkey: String,
        geohash: String,
        createdAtMillis: Long,
        content: String,
        nowMillis: Long
    ): Boolean {
        if (kind != NostrKind.EPHEMERAL_EVENT && kind != NostrKind.GEOHASH_PRESENCE) return false
        val isChat = kind == NostrKind.EPHEMERAL_EVENT && content.isNotBlank()
        return add(geohash, pubkey, createdAtMillis, if (isChat) eventId else null, nowMillis)
    }

    /** Records that [pubkey] was seen in [geohash] without writing publicly (e.g. a geohash DM). */
    fun recordPresence(geohash: String, pubkey: String, atMillis: Long, nowMillis: Long): Boolean =
        add(geohash, pubkey, atMillis, chatEventId = null, nowMillis = nowMillis)

    private fun add(geohash: String, pubkey: String, atMillis: Long, chatEventId: String?, nowMillis: Long): Boolean {
        val cell = normalize(geohash) ?: return false
        val at = atMillis.coerceAtMost(nowMillis)
        val window = if (chatEventId != null) chatWindowMillis else presenceWindowMillis
        if (at < nowMillis - window) return false

        val channel = channels[cell] ?: run {
            val (lat, lon) = Geohash.decodeToCenter(cell)
            Channel(lat, lon).also { channels[cell] = it }
        }
        val key = pubkey.lowercase()
        var changed = false
        val previous = channel.lastSeen[key]
        if (previous == null || at > previous) {
            channel.lastSeen[key] = at
            changed = true
        }
        if (chatEventId != null && chatEventId !in channel.messages) {
            if (at > (channel.lastChat[key] ?: Long.MIN_VALUE)) channel.lastChat[key] = at
            channel.messages[chatEventId] = Message(key, at)
            if (channel.messages.size > MAX_MESSAGES_PER_CHANNEL) {
                // Relay backfills arrive newest first, so arrival order is not age: drop the oldest.
                channel.messages.remove(channel.messages.entries.minBy { it.value.createdAt }.key)
            }
            changed = true
        }
        if (at > channel.lastActiveAt) channel.lastActiveAt = at
        if (channels.size > maxChannels) evictStalest()
        return changed
    }

    /** Drops activity that left its window. Returns true when anything was removed. */
    fun prune(nowMillis: Long): Boolean {
        var changed = false
        val iterator = channels.values.iterator()
        while (iterator.hasNext()) {
            val channel = iterator.next()
            if (channel.messages.values.removeAll { it.createdAt < nowMillis - chatWindowMillis }) changed = true
            if (channel.lastChat.values.removeAll { it < nowMillis - chatWindowMillis }) changed = true
            if (channel.lastSeen.entries.removeAll { (pubkey, seen) ->
                    seen < nowMillis - presenceWindowMillis && pubkey !in channel.lastChat
                }
            ) changed = true
            if (channel.lastSeen.isEmpty() && channel.messages.isEmpty()) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    /** People counted in [geohash] right now, with their newest activity in milliseconds. */
    fun activePeople(geohash: String, nowMillis: Long): Map<String, Long> {
        val channel = channels[normalize(geohash) ?: return emptyMap()] ?: return emptyMap()
        return tally(channel, nowMillis).people.associateWith { channel.lastSeen.getValue(it) }
    }

    /**
     * Every channel under the rule. [exclude] drops identities (blocked users) from every count
     * so the globe and the channel sheet agree.
     */
    fun snapshot(nowMillis: Long, exclude: (String) -> Boolean = { false }): GeohashActivitySnapshot {
        var activeChats = 0
        val result = ArrayList<GeohashChannelActivity>(channels.size)
        for ((geohash, channel) in channels) {
            val tally = tally(channel, nowMillis, exclude)
            if (tally.people.isEmpty()) continue
            if (tally.messages > 0) activeChats++
            result.add(
                GeohashChannelActivity(
                    geohash = geohash,
                    latitude = channel.latitude,
                    longitude = channel.longitude,
                    online = tally.online,
                    chatters = tally.chatters,
                    messages = tally.messages,
                    people = tally.people.size,
                    lastActiveAt = channel.lastActiveAt / 1_000
                )
            )
        }
        result.sortWith(compareByDescending<GeohashChannelActivity> { it.people }.thenByDescending { it.messages })
        return GeohashActivitySnapshot(result, estimateDistinctPeople(result) { it.online }, activeChats)
    }

    fun clear() = channels.clear()

    private class Tally(val people: Set<String>, val online: Int, val chatters: Int, val messages: Int)

    /** The rule itself: online in the presence window, or wrote in the chat window. */
    private fun tally(channel: Channel, nowMillis: Long, exclude: (String) -> Boolean = { false }): Tally {
        val chatters = HashSet<String>()
        for ((pubkey, wrote) in channel.lastChat) {
            if (wrote < nowMillis - chatWindowMillis || exclude(pubkey)) continue
            chatters.add(pubkey)
        }
        var messages = 0
        for (message in channel.messages.values) {
            if (message.createdAt < nowMillis - chatWindowMillis || exclude(message.pubkey)) continue
            messages++
        }
        val people = HashSet<String>(chatters)
        var online = 0
        for ((pubkey, seen) in channel.lastSeen) {
            if (seen < nowMillis - presenceWindowMillis || exclude(pubkey)) continue
            online++
            people.add(pubkey)
        }
        return Tally(people, online, chatters.size, messages)
    }

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
        private const val MAX_GEOHASH_LENGTH = 12
        private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
    }
}
