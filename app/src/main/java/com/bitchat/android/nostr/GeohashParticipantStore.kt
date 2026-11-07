package com.bitchat.android.nostr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * In-memory participant metadata shared by service chat handlers and foreground presence handlers.
 *
 * Who counts as a participant is decided by [GeohashActivityAggregator], the same rule the globe
 * picker uses, so the channel sheet and the globe agree.
 */
class GeohashParticipantStore(
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    internal data class Snapshot(
        /** geohash -> participant pubkey -> newest activity (ms), already filtered by the rule. */
        val participants: Map<String, Map<String, Long>> = emptyMap(),
        val nicknames: Map<String, String> = emptyMap(),
        val teleported: Set<String> = emptySet()
    )

    private val activity = GeohashActivityAggregator()
    private val mutableSnapshot = MutableStateFlow(Snapshot())
    internal val snapshots = mutableSnapshot.asStateFlow()

    /** Records a kind-20000/20001 event seen in [geohash]. */
    @Synchronized
    fun recordEvent(
        geohash: String,
        pubkey: String,
        kind: Int,
        eventId: String,
        createdAtMillis: Long,
        content: String
    ) {
        val now = nowMillis()
        if (activity.record(kind, eventId, pubkey, geohash, createdAtMillis, content, now)) {
            publish(geohash, now)
        }
    }

    /** Records that [pubkey] was active in [geohash] without a public event, e.g. a geohash DM. */
    @Synchronized
    fun updateParticipant(geohash: String, pubkey: String, lastSeenMillis: Long) {
        val now = nowMillis()
        if (activity.recordPresence(geohash, pubkey, lastSeenMillis, now)) publish(geohash, now)
    }

    @Synchronized
    fun cacheNickname(pubkey: String, nickname: String) {
        val snapshot = mutableSnapshot.value
        mutableSnapshot.value = snapshot.copy(
            nicknames = snapshot.nicknames + (pubkey.lowercase(Locale.ROOT) to nickname)
        )
    }

    @Synchronized
    fun markTeleported(pubkey: String) {
        val snapshot = mutableSnapshot.value
        mutableSnapshot.value = snapshot.copy(
            teleported = snapshot.teleported + pubkey.lowercase(Locale.ROOT)
        )
    }

    @Synchronized
    fun pruneExpired() {
        val now = nowMillis()
        activity.prune(now)
        mutableSnapshot.value = mutableSnapshot.value.copy(
            participants = activity.geohashes.associateWith { activity.activePeople(it, now) }
        )
    }

    @Synchronized
    fun clear() {
        activity.clear()
        mutableSnapshot.value = Snapshot()
    }

    private fun publish(geohash: String, now: Long) {
        val key = geohash.lowercase(Locale.ROOT)
        val snapshot = mutableSnapshot.value
        mutableSnapshot.value = snapshot.copy(
            participants = snapshot.participants + (key to activity.activePeople(key, now))
        )
    }
}
