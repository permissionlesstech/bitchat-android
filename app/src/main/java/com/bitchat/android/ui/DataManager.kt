package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import kotlin.random.Random

/**
 * Handles data persistence operations for the chat system
 */
class DataManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DataManager"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Channel-related maps that need to persist state
    private val _channelCreators = mutableMapOf<String, String>()
    private val _favoritePeers = mutableSetOf<String>()
    private val _blockedUsers = mutableSetOf<String>()
    private val _channelMembers = mutableMapOf<String, MutableSet<String>>()
    
    val channelCreators: Map<String, String> get() = _channelCreators
    val favoritePeers: Set<String> get() = _favoritePeers
    val blockedUsers: Set<String> get() = _blockedUsers
    val channelMembers: Map<String, MutableSet<String>> get() = _channelMembers
    
    // MARK: - Nickname Management
    
    fun loadNickname(): String {
        val savedNickname = prefs.getString("nickname", null)
        return if (savedNickname != null) {
            savedNickname
        } else {
            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
            saveNickname(randomNickname)
            randomNickname
        }
    }
    
    fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname).apply()
    }
    
    // MARK: - Geohash Channel Persistence
    
    fun loadLastGeohashChannel(): String? {
        return prefs.getString("last_geohash_channel", null)
    }
    
    fun saveLastGeohashChannel(channelData: String) {
        prefs.edit().putString("last_geohash_channel", channelData).apply()
        Log.d(TAG, "Saved last geohash channel: $channelData")
    }
    
    fun clearLastGeohashChannel() {
        prefs.edit().remove("last_geohash_channel").apply()
        Log.d(TAG, "Cleared last geohash channel")
    }

    // MARK: - Location Services State
    
    fun saveLocationServicesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("location_services_enabled", enabled).apply()
        Log.d(TAG, "Saved location services enabled state: $enabled")
    }
    
    fun isLocationServicesEnabled(): Boolean {
        return prefs.getBoolean("location_services_enabled", true) // Default to enabled
    }
    
    // MARK: - Channel Data Management
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        // Load joined channels
        val savedChannels = prefs.getStringSet("joined_channels", emptySet()) ?: emptySet()
        
        // Load password protected channels
        val savedProtectedChannels = prefs.getStringSet("password_protected_channels", emptySet()) ?: emptySet()
        
        // Load channel creators
        val creatorsJson = prefs.getString("channel_creators", "{}")
        try {
            val creatorsMap = gson.fromJson(creatorsJson, Map::class.java) as? Map<String, String>
            creatorsMap?.let { _channelCreators.putAll(it) }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        
        // Initialize channel members for loaded channels
        savedChannels.forEach { channel ->
            if (!_channelMembers.containsKey(channel)) {
                _channelMembers[channel] = mutableSetOf()
            }
        }
        
        return Pair(savedChannels, savedProtectedChannels)
    }
    
    fun saveChannelData(joinedChannels: Set<String>, passwordProtectedChannels: Set<String>) {
        prefs.edit().apply {
            putStringSet("joined_channels", joinedChannels)
            putStringSet("password_protected_channels", passwordProtectedChannels)
            putString("channel_creators", gson.toJson(_channelCreators))
            apply()
        }
    }
    
    fun addChannelCreator(channel: String, creatorID: String) {
        _channelCreators[channel] = creatorID
    }
    
    fun removeChannelCreator(channel: String) {
        _channelCreators.remove(channel)
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return _channelCreators[channel] == peerID
    }
    
    // MARK: - Channel Members Management
    
    fun addChannelMember(channel: String, peerID: String) {
        if (!_channelMembers.containsKey(channel)) {
            _channelMembers[channel] = mutableSetOf()
        }
        _channelMembers[channel]?.add(peerID)
    }
    
    fun removeChannelMember(channel: String, peerID: String) {
        _channelMembers[channel]?.remove(peerID)
    }
    
    fun removeChannelMembers(channel: String) {
        _channelMembers.remove(channel)
    }
    
    fun cleanupDisconnectedMembers(channel: String, connectedPeers: List<String>, myPeerID: String) {
        _channelMembers[channel]?.removeAll { memberID ->
            memberID != myPeerID && !connectedPeers.contains(memberID)
        }
    }
    
    fun cleanupAllDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        _channelMembers.values.forEach { members ->
            members.removeAll { memberID ->
                memberID != myPeerID && !connectedPeers.contains(memberID)
            }
        }
    }
    
    // MARK: - Favorites Management
    
    fun loadFavorites() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        _favoritePeers.addAll(savedFavorites)
        Log.d(TAG, "Loaded ${savedFavorites.size} favorite users from storage: $savedFavorites")
    }
    
    fun saveFavorites() {
        prefs.edit().putStringSet("favorites", _favoritePeers).apply()
        Log.d(TAG, "Saved ${_favoritePeers.size} favorite users to storage: $_favoritePeers")
    }
    
    fun addFavorite(fingerprint: String) {
        val wasAdded = _favoritePeers.add(fingerprint)
        Log.d(TAG, "addFavorite: fingerprint=$fingerprint, wasAdded=$wasAdded")
        saveFavorites()
        logAllFavorites()
    }
    
    fun removeFavorite(fingerprint: String) {
        val wasRemoved = _favoritePeers.remove(fingerprint)
        Log.d(TAG, "removeFavorite: fingerprint=$fingerprint, wasRemoved=$wasRemoved")
        saveFavorites()
        logAllFavorites()
    }
    
    fun isFavorite(fingerprint: String): Boolean {
        val result = _favoritePeers.contains(fingerprint)
        Log.d(TAG, "isFavorite check: fingerprint=$fingerprint, result=$result")
        return result
    }
    
    fun logAllFavorites() {
        Log.i(TAG, "=== ALL FAVORITE USERS ===")
        Log.i(TAG, "Total favorites: ${_favoritePeers.size}")
        _favoritePeers.forEach { fingerprint ->
            Log.i(TAG, "Favorite fingerprint: $fingerprint")
        }
        Log.i(TAG, "========================")
    }
    
    // MARK: - Blocked Users Management
    
    fun loadBlockedUsers() {
        val savedBlockedUsers = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        _blockedUsers.addAll(savedBlockedUsers)
    }
    
    fun saveBlockedUsers() {
        prefs.edit().putStringSet("blocked_users", _blockedUsers).apply()
    }
    
    fun addBlockedUser(fingerprint: String) {
        _blockedUsers.add(fingerprint)
        saveBlockedUsers()
    }
    
    fun removeBlockedUser(fingerprint: String) {
        _blockedUsers.remove(fingerprint)
        saveBlockedUsers()
    }
    
    fun isUserBlocked(fingerprint: String): Boolean {
        return _blockedUsers.contains(fingerprint)
    }
    
    // MARK: - Geohash Blocked Users Management
    
    private val _geohashBlockedUsers = mutableSetOf<String>() // Set of nostr pubkey hex
    val geohashBlockedUsers: Set<String> get() = _geohashBlockedUsers.toSet()
    
    fun loadGeohashBlockedUsers() {
        val savedGeohashBlockedUsers = prefs.getStringSet("geohash_blocked_users", emptySet()) ?: emptySet()
        _geohashBlockedUsers.addAll(savedGeohashBlockedUsers)
    }
    
    fun saveGeohashBlockedUsers() {
        prefs.edit().putStringSet("geohash_blocked_users", _geohashBlockedUsers).apply()
    }
    
    fun addGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.add(pubkeyHex)
        saveGeohashBlockedUsers()
    }
    
    fun removeGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.remove(pubkeyHex)
        saveGeohashBlockedUsers()
    }
    
    fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return _geohashBlockedUsers.contains(pubkeyHex)
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllData() {
        _channelCreators.clear()
        _favoritePeers.clear()
        _blockedUsers.clear()
        _geohashBlockedUsers.clear()
        _channelMembers.clear()
        prefs.edit().clear().apply()
    }
}
