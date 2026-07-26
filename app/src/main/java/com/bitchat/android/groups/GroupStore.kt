package com.bitchat.android.groups

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.util.hexEncodedString
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface GroupKeyStorage {
    fun get(key: String): ByteArray?
    fun put(key: String, value: ByteArray): Boolean
    fun remove(key: String): Boolean
}

internal interface GroupMetadataStorage {
    fun read(): String?
    fun write(contents: String): Boolean
    fun delete(): Boolean
}

@SuppressLint("ApplySharedPref", "UseKtx")
private class EncryptedPreferencesGroupKeyStorage(context: Context) : GroupKeyStorage {
    private val preferences: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        preferences = EncryptedSharedPreferences.create(
            context,
            "bitchat_private_groups",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun get(key: String): ByteArray? = try {
        preferences.getString(key, null)?.let {
            Base64.decode(it, Base64.NO_WRAP)
        }
    } catch (_: Exception) {
        null
    }

    override fun put(key: String, value: ByteArray): Boolean = try {
        // The metadata must not advance unless the epoch key is durably stored.
        preferences.edit()
            .putString(key, Base64.encodeToString(value, Base64.NO_WRAP))
            .commit()
    } catch (_: Exception) {
        false
    }

    override fun remove(key: String): Boolean = try {
        // Removal is a security boundary, so report the synchronous result.
        preferences.edit().remove(key).commit()
    } catch (_: Exception) {
        false
    }
}

private class FileGroupMetadataStorage(private val file: File) : GroupMetadataStorage {
    override fun read(): String? = try {
        file.takeIf(File::exists)?.readText(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    override fun write(contents: String): Boolean {
        var temporary: File? = null
        return try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false
            temporary = File(parent, "${file.name}.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        } catch (_: Exception) {
            temporary?.delete()
            false
        }
    }

    override fun delete(): Boolean = try {
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            file.parentFile
                ?.takeIf { it.listFiles().isNullOrEmpty() }
                ?.delete()
        }
        deleted
    } catch (_: Exception) {
        false
    }
}

/**
 * Persistent private-group metadata and epoch keys.
 *
 * Metadata is kept in app-private no-backup storage. Symmetric group keys are
 * stored separately in EncryptedSharedPreferences backed by Android Keystore.
 *
 * The Android constructor is intentionally inert. [initialize] performs
 * Keystore and disk access and must be called from a background dispatcher.
 */
class GroupStore private constructor(
    private val keyStorageFactory: () -> GroupKeyStorage,
    private val metadataStorageFactory: () -> GroupMetadataStorage?,
    autoInitialize: Boolean
) {
    private data class StoredMember(
        val fingerprint: String,
        val signingKey: String,
        val nickname: String
    )

    private data class StoredGroup(
        val groupID: String,
        val name: String,
        val epoch: Long,
        val members: List<StoredMember>,
        val creatorFingerprint: String
    )

    private data class StoredState(
        val version: Int = 1,
        val groups: List<StoredGroup>,
        val departures: Map<String, Long>
    )

    private val lock = Any()
    private val gson = Gson()
    private val random by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecureRandom() }
    private val _groups = MutableStateFlow<List<BitchatGroup>>(emptyList())
    private val departures = mutableMapOf<String, Long>()
    private var keyStorage: GroupKeyStorage? = null
    private var metadataStorage: GroupMetadataStorage? = null

    @Volatile
    private var initialized = false

    val groups: StateFlow<List<BitchatGroup>> = _groups.asStateFlow()
    val isReady: Boolean
        get() = initialized

    constructor(context: Context) : this(
        keyStorageFactory = {
            EncryptedPreferencesGroupKeyStorage(context.applicationContext)
        },
        metadataStorageFactory = {
            FileGroupMetadataStorage(
                File(context.applicationContext.noBackupFilesDir, "groups/groups.json")
            )
        },
        autoInitialize = false
    )

    internal constructor(
        keyStorage: GroupKeyStorage,
        metadataFile: File? = null,
        testOnly: Boolean,
        autoInitialize: Boolean = true
    ) : this(
        keyStorageFactory = { keyStorage },
        metadataStorageFactory = {
            metadataFile?.let(::FileGroupMetadataStorage)
        },
        autoInitialize = autoInitialize
    ) {
        require(testOnly)
    }

    internal constructor(
        keyStorage: GroupKeyStorage,
        metadataStorage: GroupMetadataStorage,
        testOnly: Boolean,
        autoInitialize: Boolean = true
    ) : this(
        keyStorageFactory = { keyStorage },
        metadataStorageFactory = { metadataStorage },
        autoInitialize = autoInitialize
    ) {
        require(testOnly)
    }

    init {
        if (autoInitialize) initialize()
    }

    /**
     * Initializes encrypted key storage and loads metadata. Callers using the
     * Android constructor must invoke this away from the main thread.
     */
    fun initialize(): Boolean = synchronized(lock) {
        if (initialized) return@synchronized true
        val keys = try {
            keyStorageFactory()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to initialize private-group key storage", error)
            return@synchronized false
        }
        val metadata = try {
            metadataStorageFactory()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to initialize private-group metadata storage", error)
            return@synchronized false
        }
        keyStorage = keys
        metadataStorage = metadata
        loadLocked(keys, metadata)
        initialized = true
        true
    }

    fun group(groupID: ByteArray): BitchatGroup? = synchronized(lock) {
        _groups.value.firstOrNull { it.groupID.contentEquals(groupID) }?.deepCopy()
    }

    fun group(peerID: String): BitchatGroup? =
        GroupIds.groupID(peerID)?.let(::group)

    fun key(groupID: ByteArray): ByteArray? = synchronized(lock) {
        keyStorage
            ?.get(keyName(groupID))
            ?.takeIf { it.size == BitchatGroup.KEY_LENGTH }
            ?.copyOf()
    }

    fun departureEpoch(groupID: ByteArray): Long? = synchronized(lock) {
        departures[groupID.hexEncodedString()]
    }

    fun createGroup(name: String, creator: GroupMember): BitchatGroup? {
        if (!isReady) return null
        val groupID = ByteArray(BitchatGroup.GROUP_ID_LENGTH).also(random::nextBytes)
        val key = ByteArray(BitchatGroup.KEY_LENGTH).also(random::nextBytes)
        val group = BitchatGroup(
            groupID = groupID,
            name = name,
            epoch = 1,
            members = listOf(creator),
            creatorFingerprint = creator.fingerprint
        )
        return group.takeIf { upsert(it, key) }
    }

    fun upsert(group: BitchatGroup, key: ByteArray): Boolean = synchronized(lock) {
        upsertLocked(group, key, clearDeparture = false)
    }

    fun acceptInvite(group: BitchatGroup, key: ByteArray): Boolean = synchronized(lock) {
        upsertLocked(group, key, clearDeparture = true)
    }

    fun rotateKey(
        groupID: ByteArray,
        members: List<GroupMember>
    ): Pair<BitchatGroup, ByteArray>? = synchronized(lock) {
        if (!initialized) return@synchronized null
        val existing = _groups.value.firstOrNull { it.groupID.contentEquals(groupID) }
            ?: return@synchronized null
        val newKey = ByteArray(BitchatGroup.KEY_LENGTH).also(random::nextBytes)
        val rotated = existing.copy(
            epoch = (existing.epoch + 1) and BitchatGroup.MAX_EPOCH,
            members = members.map { it.deepCopy() }
        )
        if (!upsertLocked(rotated, newKey, clearDeparture = false)) {
            return@synchronized null
        }
        rotated.deepCopy() to newKey.copyOf()
    }

    fun removeGroup(groupID: ByteArray): Boolean = synchronized(lock) {
        removeLocked(groupID, departureEpoch = null)
    }

    fun departGroup(groupID: ByteArray, epoch: Long): Boolean = synchronized(lock) {
        if (epoch !in 0..BitchatGroup.MAX_EPOCH) return@synchronized false
        removeLocked(groupID, departureEpoch = epoch)
    }

    fun wipe() = synchronized(lock) {
        if (!initialized && !initialize()) return@synchronized
        val keys = keyStorage ?: return@synchronized
        _groups.value.forEach { keys.remove(keyName(it.groupID)) }
        _groups.value = emptyList()
        departures.clear()
        metadataStorage?.delete()
    }

    private fun upsertLocked(
        group: BitchatGroup,
        key: ByteArray,
        clearDeparture: Boolean
    ): Boolean {
        val keys = keyStorage ?: return false
        if (!initialized || !isValid(group, key)) return false

        val updatedGroups = _groups.value.toMutableList()
        val index = updatedGroups.indexOfFirst { it.groupID.contentEquals(group.groupID) }
        if (index >= 0) {
            updatedGroups[index] = group.deepCopy()
        } else {
            updatedGroups += group.deepCopy()
        }
        val updatedDepartures = departures.toMutableMap()
        if (clearDeparture) updatedDepartures.remove(group.groupID.hexEncodedString())

        val name = keyName(group.groupID)
        val previousKey = keys.get(name)?.copyOf()
        if (!keys.put(name, key.copyOf())) {
            Log.e(TAG, "Failed to store private-group epoch key")
            return false
        }
        if (!persistLocked(updatedGroups, updatedDepartures)) {
            restoreKey(keys, name, previousKey)
            return false
        }

        departures.clear()
        departures.putAll(updatedDepartures)
        _groups.value = updatedGroups.map { it.deepCopy() }
        return true
    }

    private fun removeLocked(groupID: ByteArray, departureEpoch: Long?): Boolean {
        val keys = keyStorage ?: return false
        if (!initialized) return false

        val updatedGroups = _groups.value.filterNot { it.groupID.contentEquals(groupID) }
        val updatedDepartures = departures.toMutableMap()
        if (departureEpoch != null) {
            val id = groupID.hexEncodedString()
            updatedDepartures[id] = maxOf(updatedDepartures[id] ?: -1, departureEpoch)
        }

        val name = keyName(groupID)
        val previousKey = keys.get(name)?.copyOf()
        if (previousKey != null && !keys.remove(name)) {
            Log.e(TAG, "Failed to remove private-group epoch key")
            return false
        }
        if (!persistLocked(updatedGroups, updatedDepartures)) {
            restoreKey(keys, name, previousKey)
            return false
        }

        departures.clear()
        departures.putAll(updatedDepartures)
        _groups.value = updatedGroups.map { it.deepCopy() }
        return true
    }

    private fun restoreKey(
        storage: GroupKeyStorage,
        name: String,
        previousKey: ByteArray?
    ) {
        val restored = if (previousKey == null) {
            storage.remove(name)
        } else {
            storage.put(name, previousKey)
        }
        if (!restored && previousKey != null) {
            Log.e(TAG, "Failed to restore private-group epoch key after metadata failure")
        }
    }

    private fun isValid(group: BitchatGroup, key: ByteArray): Boolean =
        group.groupID.size == BitchatGroup.GROUP_ID_LENGTH &&
            key.size == BitchatGroup.KEY_LENGTH &&
            group.epoch in 0..BitchatGroup.MAX_EPOCH &&
            group.members.isNotEmpty() &&
            group.members.size <= BitchatGroup.MAX_MEMBERS &&
            group.creator != null &&
            group.members.all {
                it.fingerprint.matches(Regex("^[0-9a-fA-F]{64}$")) &&
                    it.signingKey.size == 32
            }

    private fun persistLocked(
        groups: List<BitchatGroup>,
        departures: Map<String, Long>
    ): Boolean {
        val storage = metadataStorage ?: return true
        if (groups.isEmpty() && departures.isEmpty()) return storage.delete()

        val state = StoredState(
            groups = groups.map { group ->
                StoredGroup(
                    groupID = Base64.encodeToString(group.groupID, Base64.NO_WRAP),
                    name = group.name,
                    epoch = group.epoch,
                    members = group.members.map { member ->
                        StoredMember(
                            fingerprint = member.fingerprint,
                            signingKey = Base64.encodeToString(
                                member.signingKey,
                                Base64.NO_WRAP
                            ),
                            nickname = member.nickname
                        )
                    },
                    creatorFingerprint = group.creatorFingerprint
                )
            },
            departures = departures.toSortedMap()
        )
        val persisted = storage.write(gson.toJson(state))
        if (!persisted) Log.e(TAG, "Failed to persist private-group metadata")
        return persisted
    }

    private fun loadLocked(
        keys: GroupKeyStorage,
        metadata: GroupMetadataStorage?
    ) {
        val raw = metadata?.read() ?: return
        val parsed = try {
            val json = JsonParser.parseString(raw)
            if (json.isJsonArray) {
                val type = object : TypeToken<List<StoredGroup>>() {}.type
                StoredState(groups = gson.fromJson(json, type), departures = emptyMap())
            } else {
                val objectValue = json.asJsonObject
                val groupType = object : TypeToken<List<StoredGroup>>() {}.type
                val departureType = object : TypeToken<Map<String, Long>>() {}.type
                StoredState(
                    version = objectValue.get("version")?.asInt ?: 1,
                    groups = objectValue.get("groups")?.let {
                        gson.fromJson(it, groupType)
                    } ?: emptyList(),
                    departures = objectValue.get("departures")?.let {
                        gson.fromJson(it, departureType)
                    } ?: emptyMap()
                )
            }
        } catch (_: Exception) {
            null
        } ?: return

        departures.clear()
        parsed.departures.forEach { (groupID, epoch) ->
            if (GROUP_ID_HEX.matches(groupID) && epoch in 0..BitchatGroup.MAX_EPOCH) {
                departures[groupID.lowercase()] = epoch
            }
        }

        _groups.value = parsed.groups.mapNotNull { item ->
            try {
                val group = BitchatGroup(
                    groupID = Base64.decode(item.groupID, Base64.NO_WRAP),
                    name = item.name,
                    epoch = item.epoch,
                    members = item.members.map { member ->
                        GroupMember(
                            member.fingerprint,
                            Base64.decode(member.signingKey, Base64.NO_WRAP),
                            member.nickname
                        )
                    },
                    creatorFingerprint = item.creatorFingerprint
                )
                if (departures.containsKey(group.groupID.hexEncodedString())) {
                    keys.remove(keyName(group.groupID))
                    return@mapNotNull null
                }
                val storedKey = keys.get(keyName(group.groupID))
                    ?.takeIf { it.size == BitchatGroup.KEY_LENGTH }
                    ?: return@mapNotNull null
                group.takeIf { isValid(it, storedKey) }?.deepCopy()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun keyName(groupID: ByteArray): String =
        "groupKey-${groupID.hexEncodedString()}"

    private fun BitchatGroup.deepCopy(): BitchatGroup = copy(
        groupID = groupID.copyOf(),
        members = members.map { it.deepCopy() }
    )

    private fun GroupMember.deepCopy(): GroupMember =
        copy(signingKey = signingKey.copyOf())

    companion object {
        private const val TAG = "GroupStore"
        private val GROUP_ID_HEX = Regex("^[0-9a-fA-F]{32}$")
    }
}
