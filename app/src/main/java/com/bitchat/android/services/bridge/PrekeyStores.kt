package com.bitchat.android.services.bridge

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal data class LocalPrekeyRecord(
    val id: Long,
    val privateKey: String,
    val createdAt: Long,
    var consumedAt: Long? = null
)

internal data class LocalPrekeyState(
    var records: MutableList<LocalPrekeyRecord> = mutableListOf(),
    var nextId: Long = 0,
    var generatedAt: Long = 0
)

internal data class StoredPeerPrekeyBundle(
    val noiseKey: String,
    var generatedAt: Long,
    var prekeyIds: List<Long>,
    var prekeyPublicKeys: List<String>,
    var usedIds: MutableSet<Long>,
    var assignments: MutableMap<String, Long>,
    var updatedAt: Long
)

internal interface PrekeyIdentity {
    fun staticKey(): Pair<ByteArray, ByteArray>?
    fun signingKey(): Pair<ByteArray, ByteArray>?
}

internal interface LocalPrekeyStore {
    fun load(): LocalPrekeyState
    fun save(state: LocalPrekeyState)
    fun clear()
}

internal interface PeerPrekeyStore {
    fun load(): MutableMap<String, StoredPeerPrekeyBundle>
    fun save(bundles: Map<String, StoredPeerPrekeyBundle>)
    fun clear()
}

internal class AndroidPrekeyIdentity(
    private val state: SecureIdentityStateManager
) : PrekeyIdentity {
    override fun staticKey(): Pair<ByteArray, ByteArray>? = state.loadStaticKey()
    override fun signingKey(): Pair<ByteArray, ByteArray>? = state.loadSigningKey()
}

internal class SecureLocalPrekeyStore(
    private val state: SecureIdentityStateManager,
    private val gson: Gson = Gson()
) : LocalPrekeyStore {
    override fun load(): LocalPrekeyState =
        runCatching {
            state.getSecureValue(LOCAL_STORE_KEY)
                ?.let { gson.fromJson(it, LocalPrekeyState::class.java) }
        }.getOrNull() ?: LocalPrekeyState()

    override fun save(state: LocalPrekeyState) {
        runCatching {
            this.state.storeSecureValue(LOCAL_STORE_KEY, gson.toJson(state))
        }.onFailure { Log.e(TAG, "Failed to persist local prekeys", it) }
    }

    override fun clear() {
        state.clearSecureValues(LOCAL_STORE_KEY)
    }

    private companion object {
        const val TAG = "LocalPrekeyStore"
        const val LOCAL_STORE_KEY = "courier_prekeys_v1"
    }
}

internal class SharedPreferencesPeerPrekeyStore(
    private val preferences: SharedPreferences,
    private val gson: Gson = Gson()
) : PeerPrekeyStore {
    override fun load(): MutableMap<String, StoredPeerPrekeyBundle> {
        val type = object : TypeToken<List<StoredPeerPrekeyBundle>>() {}.type
        val values: List<StoredPeerPrekeyBundle> = runCatching {
            preferences.getString(PEER_BUNDLES_KEY, null)
                ?.let { json -> gson.fromJson<List<StoredPeerPrekeyBundle>>(json, type) }
        }.getOrNull() ?: emptyList()
        return values
            .filter { it.prekeyIds.size == it.prekeyPublicKeys.size }
            .associateByTo(mutableMapOf()) { it.noiseKey }
    }

    override fun save(bundles: Map<String, StoredPeerPrekeyBundle>) {
        preferences.edit {
            putString(PEER_BUNDLES_KEY, gson.toJson(bundles.values.toList()))
        }
    }

    override fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PEER_BUNDLES_KEY = "bundles_v1"
    }
}
