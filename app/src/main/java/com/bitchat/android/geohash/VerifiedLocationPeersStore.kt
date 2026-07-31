package com.bitchat.android.geohash

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VerifiedLocationPeersStore private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "bitchat_verified_location_peers"
        private const val KEY_VERIFIED_PEERS = "verified_peers_set"
        private const val COOLDOWN_PREFIX = "cooldown_"
        private const val COOLDOWN_DURATION_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var INSTANCE: VerifiedLocationPeersStore? = null

        fun getInstance(context: Context): VerifiedLocationPeersStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VerifiedLocationPeersStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _verifiedPeersFlow = MutableStateFlow<Set<String>>(loadVerifiedPeers())
    val verifiedPeersFlow: StateFlow<Set<String>> = _verifiedPeersFlow.asStateFlow()

    private fun loadVerifiedPeers(): Set<String> {
        val raw = prefs.getStringSet(KEY_VERIFIED_PEERS, emptySet()) ?: emptySet()
        return raw.map { it.lowercase() }.toSet()
    }

    fun isVerified(peerID: String): Boolean {
        return _verifiedPeersFlow.value.contains(peerID.lowercase())
    }

    fun addVerifiedPeer(peerID: String) {
        val canonical = peerID.lowercase()
        val updated = _verifiedPeersFlow.value + canonical
        prefs.edit().putStringSet(KEY_VERIFIED_PEERS, updated).apply()
        // Clear any previous rejection cooldown when verified
        clearCooldown(canonical)
        _verifiedPeersFlow.value = updated
    }

    fun removeVerifiedPeer(peerID: String) {
        val canonical = peerID.lowercase()
        val updated = _verifiedPeersFlow.value - canonical
        prefs.edit().putStringSet(KEY_VERIFIED_PEERS, updated).apply()
        _verifiedPeersFlow.value = updated
    }

    fun recordRejection(peerID: String, durationMs: Long = COOLDOWN_DURATION_MS) {
        val canonical = peerID.lowercase()
        val expiresAt = System.currentTimeMillis() + durationMs
        prefs.edit().putLong(COOLDOWN_PREFIX + canonical, expiresAt).apply()
    }

    fun clearCooldown(peerID: String) {
        val canonical = peerID.lowercase()
        prefs.edit().remove(COOLDOWN_PREFIX + canonical).apply()
    }

    fun getRemainingCooldownMs(peerID: String): Long {
        val canonical = peerID.lowercase()
        val expiresAt = prefs.getLong(COOLDOWN_PREFIX + canonical, 0L)
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining > 0L) remaining else 0L
    }

    fun canSendRequest(peerID: String): Boolean {
        val canonical = peerID.lowercase()
        if (isVerified(canonical)) return false
        return getRemainingCooldownMs(canonical) <= 0L
    }
}
