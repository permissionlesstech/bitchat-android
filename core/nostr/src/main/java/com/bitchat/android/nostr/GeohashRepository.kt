package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.ui.GeoPerson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GeohashRepository
 * - Owns geohash participant tracking and nickname caching
 * - Maintains lightweight state for geohash-related UI
 */
class GeohashRepository(
    private val application: Application,
    private val dataManager: com.bitchat.android.ui.DataManager,
    /** Owns the coalesced presence publication; cancelling it drops a pending refresh. */
    private val scope: CoroutineScope,
    private val nostrIdentity: NostrIdentityBridge,
    private val participantStore: GeohashParticipantStore = GeohashParticipantStore(),
    /** This device's nickname, shown for our own entry in a geohash. */
    private val nickname: () -> String,
) {
    companion object {
        private const val PRESENCE_REFRESH_INTERVAL_MS = 100L
    }

    private var presenceRefreshJob: Job? = null
    private val geohashParticipants get() = participantStore.snapshots.value.participants
    private val geoNicknames get() = participantStore.snapshots.value.nicknames

    /** Refresh UI projections for chat events received by the process-owned repository too. */
    fun observeParticipants(): Job = scope.launch {
        try {
            participantStore.snapshots.collect { schedulePresenceRefresh() }
        } finally {
            synchronized(this@GeohashRepository) {
                presenceRefreshJob?.cancel()
                presenceRefreshJob = null
            }
        }
    }

    // What the geohash UI shows. Set from background threads as events arrive.
    private val _geohashPeople = MutableStateFlow<List<GeoPerson>>(emptyList())
    /** People active in the current geohash in the last five minutes, newest first. */
    val geohashPeople: StateFlow<List<GeoPerson>> = _geohashPeople.asStateFlow()

    private val _teleportedGeo = MutableStateFlow<Set<String>>(emptySet())
    /** Lowercased pubkeys of people who teleported into a geohash rather than being there. */
    val teleportedGeo: StateFlow<Set<String>> = _teleportedGeo.asStateFlow()

    private val _geohashParticipantCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Active participants per geohash. */
    val geohashParticipantCounts: StateFlow<Map<String, Int>> =
        _geohashParticipantCounts.asStateFlow()

    // conversation key (e.g., "nostr_<pub16>") -> source geohash it belongs to
    private val conversationGeohash: MutableMap<String, String> = mutableMapOf()

    @Synchronized
    fun setConversationGeohash(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            conversationGeohash[convKey] = geohash
        }
    }

    @Synchronized
    fun getConversationGeohash(convKey: String): String? = conversationGeohash[convKey]

    /**
     * Resolve a nickname, optionally with its "#abcd" pubkey suffix, to a single pubkey.
     * Returns null when the name is ambiguous so callers never act on the wrong user.
     */
    @Synchronized
    fun findPubkeyByNickname(targetNickname: String): String? {
        val targetBase = targetNickname.substringBefore("#")
        val targetSuffix = targetNickname.substringAfter("#", "").takeIf { it.isNotEmpty() }
        val matches = geoNicknames.entries.filter { (pubkey, nickname) ->
            nickname.substringBefore("#") == targetBase &&
                (targetSuffix == null || pubkey.endsWith(targetSuffix, ignoreCase = true))
        }
        return matches.singleOrNull()?.key
    }

    @Synchronized
    fun findPubkeyByShortId(shortId: String): String? {
        // First check cached nicknames (fastest)
        var found = geoNicknames.keys.firstOrNull { it.startsWith(shortId, ignoreCase = true) }
        if (found != null) return found

        // If not found in nicknames (e.g. anon user), check all known participants across all geohashes
        for (participants in geohashParticipants.values) {
            found = participants.keys.firstOrNull { it.startsWith(shortId, ignoreCase = true) }
            if (found != null) return found
        }
        
        return null
    }

    // peerID alias -> nostr pubkey mapping for geohash DMs and temp aliases
    private val nostrKeyMapping: MutableMap<String, String> = mutableMapOf()

    // Current geohash in view
    private var currentGeohash: String? = null

    fun setCurrentGeohash(geo: String?) { currentGeohash = geo }
    fun getCurrentGeohash(): String? = currentGeohash

    @Synchronized
    fun clearAll() {
        presenceRefreshJob?.cancel()
        presenceRefreshJob = null
        participantStore.clear()
        nostrKeyMapping.clear()
        _geohashPeople.value = emptyList()
        _teleportedGeo.value = emptySet()
        _geohashParticipantCounts.value = emptyMap()
        currentGeohash = null
    }

    @Synchronized
    fun cacheNickname(pubkeyHex: String, nickname: String) {
        val lower = pubkeyHex.lowercase()
        val previous = geoNicknames[lower]
        participantStore.cacheNickname(lower, nickname)
        if (previous != nickname && currentGeohash != null) {
            schedulePresenceRefresh()
        }
    }

    @Synchronized
    fun getCachedNickname(pubkeyHex: String): String? = geoNicknames[pubkeyHex.lowercase()]

    @Synchronized
    fun markTeleported(pubkeyHex: String) {
        participantStore.markTeleported(pubkeyHex)
        _teleportedGeo.value = participantStore.snapshots.value.teleported
    }

    @Synchronized
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return pubkeyHex.lowercase() in participantStore.snapshots.value.teleported
    }

    /** Counts a geohash chat or heartbeat event under the shared activity rule. */
    @Synchronized
    fun recordActivity(geohash: String, event: NostrEvent) {
        participantStore.recordEvent(
            geohash = geohash,
            pubkey = event.pubkey,
            kind = event.kind,
            eventId = event.id,
            createdAtMillis = event.createdAt * 1000L,
            content = event.content
        )
        schedulePresenceRefresh()
    }

    @Synchronized
    fun updateParticipant(geohash: String, participantId: String, lastSeen: Date) {
        participantStore.updateParticipant(geohash, participantId, lastSeen.time)
        schedulePresenceRefresh()
    }

    /** Coalesce relay bursts and shared-store updates into one UI snapshot per interval. */
    @Synchronized
    private fun schedulePresenceRefresh() {
        if (presenceRefreshJob?.isActive == true) return
        presenceRefreshJob = scope.launch {
            delay(PRESENCE_REFRESH_INTERVAL_MS)
            synchronized(this@GeohashRepository) {
                presenceRefreshJob = null
                refreshGeohashPeople()
                updateReactiveParticipantCounts()
            }
        }
    }

    @Synchronized
    fun geohashParticipantCount(geohash: String): Int {
        participantStore.pruneExpired()
        val participants = geohashParticipants[geohash] ?: return 0
        // exclude blocked users
        return participants.keys.count { !dataManager.isGeohashUserBlocked(it) }
    }

    @Synchronized
    fun refreshGeohashPeople() {
        _teleportedGeo.value = participantStore.snapshots.value.teleported
        val geohash = currentGeohash
        if (geohash == null) {
            _geohashPeople.value = emptyList()
            return
        }
        participantStore.pruneExpired()
        val participants = geohashParticipants[geohash].orEmpty()
        // exclude blocked users from people list
        val people = participants.filterKeys { !dataManager.isGeohashUserBlocked(it) }
            .map { (pubkeyHex, lastSeen) ->
            // Use our actual nickname for self; otherwise use cached nickname or anon
            val base = try {
                val myHex = currentGeohash?.let { nostrIdentity.deriveIdentity(it, application).publicKeyHex }
                if (myHex != null && myHex.equals(pubkeyHex, true)) {
                    nickname()
                } else {
                    getCachedNickname(pubkeyHex) ?: "anon"
                }
            } catch (_: Exception) { getCachedNickname(pubkeyHex) ?: "anon" }
            GeoPerson(
                id = pubkeyHex.lowercase(),
                displayName = base, // UI can add #hash if necessary
                lastSeen = Date(lastSeen)
            )
        }.sortedByDescending { it.lastSeen }
        _geohashPeople.value = people
    }

    @Synchronized
    fun updateReactiveParticipantCounts() {
        participantStore.pruneExpired()
        val counts = mutableMapOf<String, Int>()
        for ((gh, participants) in geohashParticipants) {
            counts[gh] = participants.keys.count { !dataManager.isGeohashUserBlocked(it) }
        }
        _geohashParticipantCounts.value = counts
    }

    @Synchronized
    fun putNostrKeyMapping(tempKeyOrPeer: String, pubkeyHex: String) {
        nostrKeyMapping[tempKeyOrPeer] = pubkeyHex
    }

    @Synchronized
    fun getNostrKeyMapping(): Map<String, String> = nostrKeyMapping.toMap()

    @Synchronized
    fun displayNameForNostrPubkey(pubkeyHex: String): String {
        val suffix = pubkeyHex.takeLast(4)
        val lower = pubkeyHex.lowercase()
        // Self nickname if matches current identity of current geohash
        val current = currentGeohash
        if (current != null) {
            try {
                val my = nostrIdentity.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    return "${nickname()}#$suffix"
                }
            } catch (_: Exception) {}
        }
        val nick = geoNicknames[lower] ?: "anon"
        return "$nick#$suffix"
    }

    @Synchronized
    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val current = currentGeohash
        val base: String = try {
            if (current != null) {
                val my = nostrIdentity.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    nickname()
                } else geoNicknames[lower] ?: "anon"
            } else geoNicknames[lower] ?: "anon"
        } catch (_: Exception) { geoNicknames[lower] ?: "anon" }
        if (current == null) return base
        return try {
            val participants = geohashParticipants[current] ?: emptyMap()
            var count = 0
            for (k in participants.keys) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                val name = if (k.equals(lower, true)) base else (geoNicknames[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participants.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }

    /**
     * Get display name for any geohash (not just current one) for header titles
     */
    @Synchronized
    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val base = geoNicknames[lower] ?: "anon"
        return try {
            val participants = geohashParticipants[sourceGeohash] ?: emptyMap()
            var count = 0
            for (k in participants.keys) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                val name = if (k.equals(lower, true)) base else (geoNicknames[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participants.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }
}
