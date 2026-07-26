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
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface GroupKeyStorage {
    fun get(key: String): ByteArray?
    fun put(key: String, value: ByteArray): Boolean
    fun remove(key: String): Boolean
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

/**
 * Persistent private-group metadata and epoch keys.
 *
 * Metadata is kept in app-private no-backup storage. Symmetric group keys are
 * stored separately in EncryptedSharedPreferences backed by Android Keystore.
 */
class GroupStore private constructor(
    private val keyStorage: GroupKeyStorage,
    private val metadataFile: File?
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

    private val lock = Any()
    private val gson = Gson()
    private val random = SecureRandom()
    private val _groups = MutableStateFlow<List<BitchatGroup>>(emptyList())
    val groups: StateFlow<List<BitchatGroup>> = _groups.asStateFlow()

    constructor(context: Context) : this(
        EncryptedPreferencesGroupKeyStorage(context.applicationContext),
        File(context.noBackupFilesDir, "groups/groups.json")
    )

    internal constructor(
        keyStorage: GroupKeyStorage,
        metadataFile: File? = null,
        testOnly: Boolean
    ) : this(keyStorage, metadataFile) {
        require(testOnly)
    }

    init {
        loadFromDisk()
    }

    fun group(groupID: ByteArray): BitchatGroup? = synchronized(lock) {
        _groups.value.firstOrNull { it.groupID.contentEquals(groupID) }
    }

    fun group(peerID: String): BitchatGroup? =
        GroupIds.groupID(peerID)?.let(::group)

    fun key(groupID: ByteArray): ByteArray? =
        keyStorage.get(keyName(groupID))?.takeIf { it.size == BitchatGroup.KEY_LENGTH }

    fun createGroup(name: String, creator: GroupMember): BitchatGroup? {
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
        if (!isValid(group, key)) return@synchronized false
        if (!keyStorage.put(keyName(group.groupID), key.copyOf())) {
            Log.e(TAG, "Failed to store private-group epoch key")
            return@synchronized false
        }
        val updated = _groups.value.toMutableList()
        val index = updated.indexOfFirst { it.groupID.contentEquals(group.groupID) }
        if (index >= 0) {
            updated[index] = group.deepCopy()
        } else {
            updated += group.deepCopy()
        }
        _groups.value = updated
        persistLocked()
        true
    }

    fun rotateKey(
        groupID: ByteArray,
        members: List<GroupMember>
    ): Pair<BitchatGroup, ByteArray>? = synchronized(lock) {
        val existing = _groups.value.firstOrNull { it.groupID.contentEquals(groupID) }
            ?: return@synchronized null
        val newKey = ByteArray(BitchatGroup.KEY_LENGTH).also(random::nextBytes)
        val rotated = existing.copy(
            epoch = (existing.epoch + 1) and BitchatGroup.MAX_EPOCH,
            members = members.map { it.deepCopy() }
        )
        if (!upsert(rotated, newKey)) return@synchronized null
        rotated to newKey
    }

    fun removeGroup(groupID: ByteArray) = synchronized(lock) {
        _groups.value = _groups.value.filterNot { it.groupID.contentEquals(groupID) }
        keyStorage.remove(keyName(groupID))
        persistLocked()
    }

    fun wipe() = synchronized(lock) {
        _groups.value.forEach { keyStorage.remove(keyName(it.groupID)) }
        _groups.value = emptyList()
        try {
            metadataFile?.delete()
            metadataFile?.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        } catch (_: Exception) {
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

    private fun persistLocked() {
        val file = metadataFile ?: return
        try {
            if (_groups.value.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val stored = _groups.value.map { group ->
                StoredGroup(
                    groupID = Base64.encodeToString(group.groupID, Base64.NO_WRAP),
                    name = group.name,
                    epoch = group.epoch,
                    members = group.members.map { member ->
                        StoredMember(
                            fingerprint = member.fingerprint,
                            signingKey = Base64.encodeToString(member.signingKey, Base64.NO_WRAP),
                            nickname = member.nickname
                        )
                    },
                    creatorFingerprint = group.creatorFingerprint
                )
            }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(stored).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist private-group metadata: ${error.message}")
        }
    }

    private fun loadFromDisk() = synchronized(lock) {
        val file = metadataFile ?: return@synchronized
        val stored = try {
            if (!file.exists()) return@synchronized
            val type = object : TypeToken<List<StoredGroup>>() {}.type
            gson.fromJson<List<StoredGroup>>(file.readText(Charsets.UTF_8), type)
        } catch (_: Exception) {
            null
        } ?: return@synchronized

        _groups.value = stored.mapNotNull { item ->
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
                val storedKey = key(group.groupID) ?: return@mapNotNull null
                group.takeIf { isValid(it, storedKey) }
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
    }
}
