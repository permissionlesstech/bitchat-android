package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson

/**
 * Persistent store for message IDs we've already acknowledged (DELIVERED), READ,
 * or durably committed from the pairwise ratchet.
 * Limits to last MAX_IDS entries per set to avoid memory bloat.
 */
class SeenMessageStore private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val MAX_IDS = com.bitchat.android.util.AppConstants.Services.SEEN_MESSAGE_MAX_IDS

        @Volatile private var INSTANCE: SeenMessageStore? = null
        fun getInstance(appContext: Context): SeenMessageStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SeenMessageStore(appContext.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private val secure = SecureIdentityStateManager(context)

    private val delivered = LinkedHashSet<String>(MAX_IDS)
    private val read = LinkedHashSet<String>(MAX_IDS)
    private val ndrProcessed = LinkedHashSet<String>(MAX_IDS)

    init { load() }

    @Synchronized fun hasDelivered(id: String) = delivered.contains(id)
    @Synchronized fun hasRead(id: String) = read.contains(id)
    @Synchronized fun hasProcessedNdr(id: String) = ndrProcessed.contains(id)

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

    /**
     * Returns only after the processed marker is committed to encrypted
     * preferences. The pairwise action must not be acknowledged when this
     * returns false.
     */
    @Synchronized fun markProcessedNdr(id: String): Boolean {
        if (ndrProcessed.contains(id)) return true
        val previous = ndrProcessed.toList()
        ndrProcessed.add(id)
        trim(ndrProcessed)
        if (persistSynchronously()) return true
        ndrProcessed.clear()
        ndrProcessed.addAll(previous)
        return false
    }

    @Synchronized fun clear() {
        delivered.clear()
        read.clear()
        ndrProcessed.clear()
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
            val data = gson.fromJson(json, StorePayload::class.java) ?: return
            delivered.clear(); read.clear(); ndrProcessed.clear()
            data.delivered.orEmpty().takeLast(MAX_IDS).forEach { delivered.add(it) }
            data.read.orEmpty().takeLast(MAX_IDS).forEach { read.add(it) }
            data.ndrProcessed.orEmpty().takeLast(MAX_IDS).forEach { ndrProcessed.add(it) }
            Log.d(
                TAG,
                "Loaded delivered=${delivered.size}, read=${read.size}, ndr=${ndrProcessed.size}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SeenMessageStore: ${e.message}")
        }
    }

    @Synchronized private fun persist() {
        try {
            val payload = currentPayload()
            val json = gson.toJson(payload)
            secure.storeSecureValue(STORAGE_KEY, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
        }
    }

    @Synchronized private fun persistSynchronously(): Boolean = try {
        secure.storeSecureValueSynchronously(STORAGE_KEY, gson.toJson(currentPayload()))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to durably persist SeenMessageStore: ${e.message}")
        false
    }

    private fun currentPayload() = StorePayload(
        delivered = delivered.toList(),
        read = read.toList(),
        ndrProcessed = ndrProcessed.toList()
    )

    private data class StorePayload(
        val delivered: List<String>? = emptyList(),
        val read: List<String>? = emptyList(),
        val ndrProcessed: List<String>? = emptyList()
    )
}
