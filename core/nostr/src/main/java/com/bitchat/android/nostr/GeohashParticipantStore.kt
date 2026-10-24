package com.bitchat.android.nostr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** In-memory participant metadata shared by service chat handlers and foreground presence handlers. */
class GeohashParticipantStore(
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    internal data class Snapshot(
        val participants: Map<String, Map<String, Long>> = emptyMap(),
        val nicknames: Map<String, String> = emptyMap(),
        val teleported: Set<String> = emptySet()
    )

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    internal val snapshots = mutableSnapshot.asStateFlow()
    internal val cutoffMillis: Long get() = nowMillis() - 5 * 60 * 1000L

    @Synchronized
    fun updateParticipant(geohash: String, pubkey: String, lastSeenMillis: Long) {
        val snapshot = mutableSnapshot.value
        val participants = snapshot.participants[geohash].orEmpty()
        val key = pubkey.lowercase(Locale.ROOT)
        // Relays can replay newest-first or send future timestamps. Preserve the freshest
        // observation without allowing a future event to pin an identity online.
        val effective = lastSeenMillis.coerceAtMost(nowMillis())
        if (effective <= (participants[key] ?: Long.MIN_VALUE)) return
        mutableSnapshot.value = snapshot.copy(
            participants = snapshot.participants + (geohash to (participants + (key to effective)))
        )
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
        val cutoff = cutoffMillis
        val snapshot = mutableSnapshot.value
        mutableSnapshot.value = snapshot.copy(
            participants = snapshot.participants.mapValues { (_, people) ->
                people.filterValues { it >= cutoff }
            }
        )
    }

    @Synchronized
    fun clear() {
        mutableSnapshot.value = Snapshot()
    }
}
