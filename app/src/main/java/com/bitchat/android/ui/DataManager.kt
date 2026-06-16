package com.bitchat.android.ui

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bitchat.android.core.data.bitchatPrefsDataStore
import com.bitchat.android.crypto.TinkAeadProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles data persistence operations for the chat system
 */
class DataManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DataManager"
        private const val LEGACY_PREFS_NAME = "bitchat_prefs"
    }

    private object Keys {
        val migrationDone = stringPreferencesKey("migration_done")
        val nickname = stringPreferencesKey("nickname")
        val lastGeohashChannel = stringPreferencesKey("last_geohash_channel")
        val locationServicesEnabled = stringPreferencesKey("location_services_enabled")
        val joinedChannels = stringPreferencesKey("joined_channels")
        val passwordProtectedChannels = stringPreferencesKey("password_protected_channels")
        val channelCreators = stringPreferencesKey("channel_creators")
        val favorites = stringPreferencesKey("favorites")
        val blockedUsers = stringPreferencesKey("blocked_users")
        val geohashBlockedUsers = stringPreferencesKey("geohash_blocked_users")
    }


    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    private val initSignal = CompletableDeferred<Unit>()

    @Volatile
    private var initialized = false

    private val dataStore = context.bitchatPrefsDataStore
    private val gson = Gson()
    
    // Channel-related maps that need to persist state
    private val channelCreatorsCache = AtomicReference<Map<String, String>>(emptyMap())
    private val favoritePeersCache = AtomicReference<Set<String>>(emptySet())
    private val blockedUsersCache = AtomicReference<Set<String>>(emptySet())
    private val geohashBlockedUsersCache = AtomicReference<Set<String>>(emptySet())
    private val _channelMembers = mutableMapOf<String, MutableSet<String>>()

    val channelCreators: Map<String, String> get() = channelCreatorsCache.get()
    val favoritePeers: Set<String> get() = favoritePeersCache.get()
    val blockedUsers: Set<String> get() = blockedUsersCache.get()
    val channelMembers: Map<String, MutableSet<String>> get() = _channelMembers
    private val nicknameCache = AtomicReference<String?>(null)
    private val lastGeohashChannelCache = AtomicReference<String?>(null)
    private val locationServicesEnabledCache = AtomicReference(true)
    private val joinedChannelsCache = AtomicReference<Set<String>>(emptySet())
    private val passwordProtectedChannelsCache = AtomicReference<Set<String>>(emptySet())

    val geohashBlockedUsers: Set<String> get() = geohashBlockedUsersCache.get()

    private var cacheSyncJob: Job? = null

    suspend fun initialize() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            startCacheSyncIfNeeded()
            initSignal.await()
            initialized = true
        }
    }

    // MARK: - Nickname Management
    
    fun loadNickname(): String {
        val savedNickname = nicknameCache.get()
        return if (!savedNickname.isNullOrBlank()) {
            savedNickname
        } else {
            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
            saveNickname(randomNickname)
            randomNickname
        }
    }
    
    fun saveNickname(nickname: String) {
        nicknameCache.set(nickname)
        persistString(Keys.nickname, nickname)
    }
    
    // MARK: - Geohash Channel Persistence

    fun loadLastGeohashChannel(): String? = lastGeohashChannelCache.get()

    fun saveLastGeohashChannel(channelData: String) {
        lastGeohashChannelCache.set(channelData)
        persistString(Keys.lastGeohashChannel, channelData)
        Log.d(TAG, "Saved last geohash channel: $channelData")
    }
    
    fun clearLastGeohashChannel() {
        lastGeohashChannelCache.set(null)
        ioScope.launch {
            runCatching {
                dataStore.edit { prefs -> prefs.remove(Keys.lastGeohashChannel) }
            }.onFailure { e ->
                Log.e(TAG, "Failed to clear last geohash channel: ${e.message}")
            }
        }
        Log.d(TAG, "Cleared last geohash channel")
    }

    // MARK: - Location Services State
    
    fun saveLocationServicesEnabled(enabled: Boolean) {
        locationServicesEnabledCache.set(enabled)
        persistString(Keys.locationServicesEnabled, enabled.toString())
        Log.d(TAG, "Saved location services enabled state: $enabled")
    }

    fun isLocationServicesEnabled(): Boolean = locationServicesEnabledCache.get()

    // MARK: - Channel Data Management
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        return Pair(joinedChannelsCache.get(), passwordProtectedChannelsCache.get())
    }
    
    fun saveChannelData(joinedChannels: Set<String>, passwordProtectedChannels: Set<String>) {
        joinedChannelsCache.set(joinedChannels)
        passwordProtectedChannelsCache.set(passwordProtectedChannels)
        val creatorsSnapshot = channelCreatorsCache.get()
        ioScope.launch {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[Keys.joinedChannels] = encryptString(Keys.joinedChannels.name, gson.toJson(joinedChannels))
                    prefs[Keys.passwordProtectedChannels] = encryptString(
                        Keys.passwordProtectedChannels.name,
                        gson.toJson(passwordProtectedChannels)
                    )
                    prefs[Keys.channelCreators] = encryptString(
                        Keys.channelCreators.name,
                        gson.toJson(creatorsSnapshot)
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to persist channel data: ${e.message}")
            }
        }
    }
    
    fun addChannelCreator(channel: String, creatorID: String) {
        val current = channelCreatorsCache.get()
        val updated = Collections.unmodifiableMap(current.toMutableMap().apply { put(channel, creatorID) })
        channelCreatorsCache.set(updated)
    }
    
    fun removeChannelCreator(channel: String) {
        val current = channelCreatorsCache.get()
        if (!current.containsKey(channel)) return
        val updated = Collections.unmodifiableMap(current.toMutableMap().apply { remove(channel) })
        channelCreatorsCache.set(updated)
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return channelCreatorsCache.get()[channel] == peerID
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
        val current = favoritePeers
        Log.d(TAG, "Loaded ${current.size} favorite users from storage: $current")
    }
    
    fun saveFavorites() {
        val snapshot = favoritePeersCache.get()
        persistStringSet(Keys.favorites, snapshot)
        Log.d(TAG, "Saved ${snapshot.size} favorite users to storage: $snapshot")
    }
    
    fun addFavorite(fingerprint: String) {
        val current = favoritePeersCache.get()
        val updated = Collections.unmodifiableSet((current + fingerprint).toSet())
        val wasAdded = updated.size != current.size
        favoritePeersCache.set(updated)
        Log.d(TAG, "addFavorite: fingerprint=$fingerprint, wasAdded=$wasAdded")
        saveFavorites()
        logAllFavorites()
    }
    
    fun removeFavorite(fingerprint: String) {
        val current = favoritePeersCache.get()
        val updated = Collections.unmodifiableSet((current - fingerprint).toSet())
        val wasRemoved = updated.size != current.size
        favoritePeersCache.set(updated)
        Log.d(TAG, "removeFavorite: fingerprint=$fingerprint, wasRemoved=$wasRemoved")
        saveFavorites()
        logAllFavorites()
    }
    
    fun isFavorite(fingerprint: String): Boolean {
        val result = favoritePeersCache.get().contains(fingerprint)
        Log.d(TAG, "isFavorite check: fingerprint=$fingerprint, result=$result")
        return result
    }
    
    fun logAllFavorites() {
        val current = favoritePeers
        Log.i(TAG, "=== ALL FAVORITE USERS ===")
        Log.i(TAG, "Total favorites: ${current.size}")
        current.forEach { fingerprint ->
            Log.i(TAG, "Favorite fingerprint: $fingerprint")
        }
        Log.i(TAG, "========================")
    }
    
    // MARK: - Blocked Users Management
    
    fun loadBlockedUsers() {
        val current = blockedUsers
        Log.d(TAG, "Loaded ${current.size} blocked users from storage")
    }
    
    fun saveBlockedUsers() {
        persistStringSet(Keys.blockedUsers, blockedUsersCache.get())
    }
    
    fun addBlockedUser(fingerprint: String) {
        val updated = Collections.unmodifiableSet((blockedUsersCache.get() + fingerprint).toSet())
        blockedUsersCache.set(updated)
        saveBlockedUsers()
    }
    
    fun removeBlockedUser(fingerprint: String) {
        val updated = Collections.unmodifiableSet((blockedUsersCache.get() - fingerprint).toSet())
        blockedUsersCache.set(updated)
        saveBlockedUsers()
    }
    
    fun isUserBlocked(fingerprint: String): Boolean {
        return blockedUsersCache.get().contains(fingerprint)
    }
    
    // MARK: - Geohash Blocked Users Management

    fun loadGeohashBlockedUsers() {
        val current = geohashBlockedUsers
        Log.d(TAG, "Loaded ${current.size} geohash blocked users from storage")
    }
    
    fun saveGeohashBlockedUsers() {
        persistStringSet(Keys.geohashBlockedUsers, geohashBlockedUsersCache.get())
    }
    
    fun addGeohashBlockedUser(pubkeyHex: String) {
        val updated = Collections.unmodifiableSet((geohashBlockedUsersCache.get() + pubkeyHex).toSet())
        geohashBlockedUsersCache.set(updated)
        saveGeohashBlockedUsers()
    }
    
    fun removeGeohashBlockedUser(pubkeyHex: String) {
        val updated = Collections.unmodifiableSet((geohashBlockedUsersCache.get() - pubkeyHex).toSet())
        geohashBlockedUsersCache.set(updated)
        saveGeohashBlockedUsers()
    }
    
    fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return geohashBlockedUsersCache.get().contains(pubkeyHex)
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllData() {
        clearCache()
        ioScope.launch {
            runCatching { dataStore.edit { it.clear() } }
                .onFailure { e -> Log.e(TAG, "Failed to clear DataStore: ${e.message}") }
        }
    }

    private suspend fun migrateFromLegacyPrefsIfNeeded() {
        val current = dataStore.data.first()
        if (current.contains(Keys.migrationDone)) return
        if (current[Keys.nickname] != null ||
            current[Keys.joinedChannels] != null ||
            current[Keys.favorites] != null ||
            current[Keys.blockedUsers] != null) {
            return
        }

        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (legacyPrefs.all.isEmpty()) {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[Keys.migrationDone] = encryptString(Keys.migrationDone.name, "true")
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to mark migration done: ${e.message}")
            }
            return
        }

        try {
            dataStore.edit { prefs ->
                legacyPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is String -> prefs[stringPreferencesKey(key)] = encryptString(key, value)
                        is Boolean -> prefs[stringPreferencesKey(key)] = encryptString(key, value.toString())
                        is Set<*> -> {
                            val set = value.filterIsInstance<String>().toSet()
                            prefs[stringPreferencesKey(key)] = encryptString(key, gson.toJson(set))
                        }
                        else -> Unit
                    }
                }
                prefs[Keys.migrationDone] = encryptString(Keys.migrationDone.name, "true")
            }
            context.deleteSharedPreferences(LEGACY_PREFS_NAME)
            Log.i(TAG, "Migrated legacy SharedPreferences to DataStore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy prefs: ${e.message}")
        }
    }

    private fun startCacheSyncIfNeeded() {
        if (cacheSyncJob != null) return
        cacheSyncJob = ioScope.launch {
            runCatching { migrateFromLegacyPrefsIfNeeded() }
                .onFailure { e -> Log.e(TAG, "Migration failed: ${e.message}") }

            try {
                dataStore.data.collect { prefs ->
                    var decryptionFailed = false
                    val cleanupKeys = mutableListOf<Preferences.Key<String>>()

                    fun readString(key: Preferences.Key<String>): String? {
                        val encoded = prefs[key] ?: return null
                        return try {
                            decryptString(key.name, encoded)
                        } catch (e: Exception) {
                            decryptionFailed = true
                            Log.e(TAG, "Failed to decrypt ${key.name}: ${e.message}")
                            cleanupKeys.add(key)
                            null
                        }
                    }

                    nicknameCache.set(readString(Keys.nickname))
                    lastGeohashChannelCache.set(readString(Keys.lastGeohashChannel))
                    locationServicesEnabledCache.set(
                        readString(Keys.locationServicesEnabled)?.toBooleanStrictOrNull() ?: true
                    )

                    joinedChannelsCache.set(readStringSet(readString(Keys.joinedChannels)))
                    passwordProtectedChannelsCache.set(readStringSet(readString(Keys.passwordProtectedChannels)))

                    val creatorsJson = readString(Keys.channelCreators) ?: "{}"
                    val creatorsType = object : TypeToken<Map<String, String>>() {}.type
                    val creatorsMap = runCatching {
                        gson.fromJson<Map<String, String>>(creatorsJson, creatorsType)
                    }.getOrDefault(emptyMap())

                    channelCreatorsCache.set(Collections.unmodifiableMap(creatorsMap))
                    favoritePeersCache.set(Collections.unmodifiableSet(readStringSet(readString(Keys.favorites))))
                    blockedUsersCache.set(Collections.unmodifiableSet(readStringSet(readString(Keys.blockedUsers))))
                    geohashBlockedUsersCache.set(
                        Collections.unmodifiableSet(readStringSet(readString(Keys.geohashBlockedUsers)))
                    )

                    if (decryptionFailed) {
                        Log.e(TAG, "Decryption failed; clearing all DataStore data")
                        clearAllData()
                    } else {
                        val currentNickname = nicknameCache.get()
                        if (currentNickname.isNullOrBlank()) {
                            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
                            nicknameCache.set(randomNickname)
                            persistString(Keys.nickname, randomNickname)
                        }

                        if (cleanupKeys.isNotEmpty()) {
                            ioScope.launch {
                                runCatching {
                                    dataStore.edit { editPrefs ->
                                        cleanupKeys.forEach { editPrefs.remove(it) }
                                    }
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to cleanup corrupted keys: ${e.message}")
                                }
                            }
                        }
                    }

                    if (!initSignal.isCompleted) {
                        initSignal.complete(Unit)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cache sync failed: ${e.message}")
                if (!initSignal.isCompleted) {
                    initSignal.complete(Unit)
                }
            }
        }
    }

    private fun clearCache() {
        channelCreatorsCache.set(emptyMap())
        favoritePeersCache.set(emptySet())
        blockedUsersCache.set(emptySet())
        geohashBlockedUsersCache.set(emptySet())
        _channelMembers.clear()
        joinedChannelsCache.set(emptySet())
        passwordProtectedChannelsCache.set(emptySet())
        nicknameCache.set(null)
        lastGeohashChannelCache.set(null)
        locationServicesEnabledCache.set(true)
    }

    private fun persistString(key: Preferences.Key<String>, value: String) {
        ioScope.launch {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[key] = encryptString(key.name, value)
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to persist ${key.name}: ${e.message}")
            }
        }
    }

    private fun persistStringSet(key: Preferences.Key<String>, values: Set<String>) {
        persistString(key, gson.toJson(values))
    }

    private fun readStringSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        val type = object : TypeToken<Set<String>>() {}.type
        return runCatching { gson.fromJson<Set<String>>(json, type) }.getOrDefault(emptySet())
    }

    private fun encryptString(keyName: String, value: String): String {
        val aad = keyName.toByteArray(Charsets.UTF_8)
        val ciphertext = TinkAeadProvider.getAead(context).encrypt(value.toByteArray(Charsets.UTF_8), aad)
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decryptString(keyName: String, encoded: String): String {
        val aad = keyName.toByteArray(Charsets.UTF_8)
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        val plaintext = TinkAeadProvider.getAead(context).decrypt(ciphertext, aad)
        return String(plaintext, Charsets.UTF_8)
    }
}
