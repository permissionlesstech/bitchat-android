package com.bitchat.android.services

import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import kotlinx.serialization.Serializable
import com.bitchat.android.util.JsonUtil
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * Persistent store for message IDs we've already acknowledged (DELIVERED) or READ.
 * Limits to last MAX_IDS entries per set to avoid memory bloat.
 */
@Singleton
class SeenMessageStore @Inject constructor(
    private val secure: SecureIdentityStateManager
) {
    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val MAX_IDS = com.bitchat.android.util.AppConstants.Services.SEEN_MESSAGE_MAX_IDS
    }


    private val delivered = LinkedHashSet<String>(MAX_IDS)
    private val read = LinkedHashSet<String>(MAX_IDS)

    init { load() }

    @Synchronized fun hasDelivered(id: String) = delivered.contains(id)
    @Synchronized fun hasRead(id: String) = read.contains(id)

    @Synchronized fun markDelivered(id: String) {
        if (delivered.remove(id)) delivered.add(id) else {
            delivered.add(id)
            trim(delivered)
        }
        persist()
    }

    @Synchronized fun markRead(id: String) {
        if (read.remove(id)) read.add(id) else {
            read.add(id)
            trim(read)
        }
        persist()
    }

    private fun trim(set: LinkedHashSet<String>) {
        if (set.size <= MAX_IDS) return
        val it = set.iterator()
        while (set.size > MAX_IDS && it.hasNext()) {
            it.next(); it.remove()
        }
    }

    @Synchronized private fun load() {
        try {
            val json = secure.getSecureValue(STORAGE_KEY) ?: return
            val data = JsonUtil.fromJsonOrNull<StorePayload>(json) ?: return
            delivered.clear(); read.clear()
            data.delivered.takeLast(MAX_IDS).forEach { delivered.add(it) }
            data.read.takeLast(MAX_IDS).forEach { read.add(it) }
            Log.d(TAG, "Loaded delivered=${delivered.size}, read=${read.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SeenMessageStore: ${e.message}")
        }
    }

    @Synchronized private fun persist() {
        try {
            val payload = StorePayload(delivered.toList(), read.toList())
            val json = JsonUtil.toJson(payload)
            secure.storeSecureValue(STORAGE_KEY, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
        }
    }

    @Serializable
    private data class StorePayload(
        val delivered: List<String> = emptyList(),
        val read: List<String> = emptyList()
    )
}
