package com.bitchat.android.services

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persistent store for message IDs we've already acknowledged as delivered, read locally, or
 * admitted to a completed read-receipt send window.
 *
 * Local read state must not be used as proof that a read-receipt packet reached the sender.
 * Transport delivery is best-effort and retryable, while local read state drives unread UI.
 * Limits to last MAX_IDS entries per set to avoid memory bloat.
 */
class SeenMessageStore private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val MAX_IDS = com.bitchat.android.util.AppConstants.Services.SEEN_MESSAGE_MAX_IDS

        // The constructor always receives applicationContext, so process lifetime is intentional.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: SeenMessageStore? = null
        fun getInstance(appContext: Context): SeenMessageStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SeenMessageStore(appContext.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private val secure = SecureIdentityStateManager(context)
    private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var persistScope = CoroutineScope(persistDispatcher + SupervisorJob())
    private val persistLock = Any()
    private var pendingPersistJob: Job? = null
    private var mutationGeneration: Long = 0L
    private var persistedGeneration: Long = 0L

    private val delivered = LinkedHashSet<String>()
    private val locallyRead = LinkedHashSet<String>()
    private val readReceiptsSent = LinkedHashSet<String>()

    init { load() }

    @Synchronized fun hasDelivered(id: String) = delivered.contains(id)
    @Synchronized fun hasBeenReadLocally(id: String) = locallyRead.contains(id)
    @Synchronized fun hasReadReceiptBeenSent(id: String) = readReceiptsSent.contains(id)

    @Synchronized fun markDelivered(id: String) {
        if (delivered.contains(id)) return
        delivered.add(id)
        trim(delivered)
        mutationGeneration++
        schedulePersistLocked()
    }

    @Synchronized fun markReadLocally(id: String) {
        val added = if (locallyRead.contains(id)) false else {
            locallyRead.add(id)
            trim(locallyRead)
            mutationGeneration++
            true
        }
        AppStateStore.markPrivateMessageRead(id)
        if (added) schedulePersistLocked()
    }

    @Synchronized fun markReadReceiptSent(id: String) {
        if (readReceiptsSent.contains(id)) return
        readReceiptsSent.add(id)
        trim(readReceiptsSent)
        mutationGeneration++
        schedulePersistLocked()
    }

    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        val changed = synchronized(this) {
            val modified = delivered.removeAll(ids) or
                locallyRead.removeAll(ids) or
                readReceiptsSent.removeAll(ids)
            if (modified) {
                mutationGeneration++
                pendingPersistJob?.cancel()
                pendingPersistJob = null
            }
            modified
        }
        if (!changed) return
        // Enqueue immediate serialized IO write so UI conversation deletion never blocks the main thread.
        persistScope.launch {
            persistLatest()
        }
    }

    fun clear() {
        synchronized(this) {
            mutationGeneration++
            pendingPersistJob?.cancel()
            pendingPersistJob = null
            delivered.clear()
            locallyRead.clear()
            readReceiptsSent.clear()
        }
        // Panic wipe must commit synchronously before returning, with JSON serialized outside persistLock.
        persistLatest()
    }

    /**
     * Bounded-latency batching: never cancel/restart an active 500 ms timer, so continuous
     * mutations cannot starve persistence indefinitely.
     */
    private fun schedulePersistLocked() {
        if (pendingPersistJob?.isActive == true) return
        pendingPersistJob = persistScope.launch {
            delay(500L)
            persistLatest()
            // If more mutations arrived while persistLatest() ran, schedule one follow-up flush.
            synchronized(this@SeenMessageStore) {
                pendingPersistJob = null
                if (persistedGeneration != mutationGeneration) {
                    schedulePersistLocked()
                }
            }
        }
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
            delivered.clear(); locallyRead.clear(); readReceiptsSent.clear()
            data.delivered.takeLast(MAX_IDS).forEach { delivered.add(it) }
            data.locallyRead.takeLast(MAX_IDS).forEach { locallyRead.add(it) }
            // Older payloads used the local-read set to suppress receipt sends. Seed the new
            // explicit set once during migration to avoid replaying an entire chat history.
            (data.readReceiptsSent ?: data.locallyRead)
                .takeLast(MAX_IDS)
                .forEach { readReceiptsSent.add(it) }
            Log.d(
                TAG,
                "Loaded delivered=${delivered.size}, locallyRead=${locallyRead.size}, " +
                    "readReceiptsSent=${readReceiptsSent.size}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SeenMessageStore: ${e.message}")
        }
    }

    /**
     * Write only if no newer mutation, removal, or panic clear has occurred since [targetGeneration].
     * JSON serialization happens outside [persistLock] so callers never stall behind serialization.
     */
    internal fun persistIfCurrent(targetGeneration: Long) {
        try {
            val payload = synchronized(this) {
                if (mutationGeneration != targetGeneration) return
                snapshotLocked()
            }
            val json = gson.toJson(payload)
            synchronized(persistLock) {
                synchronized(this) {
                    if (mutationGeneration != targetGeneration) return
                }
                secure.storeSecureValue(STORAGE_KEY, json)
                synchronized(this) {
                    if (mutationGeneration == targetGeneration) {
                        persistedGeneration = targetGeneration
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
        }
    }

    private fun persistLatest() {
        try {
            while (true) {
                val (gen, payload) = synchronized(this) {
                    mutationGeneration to snapshotLocked()
                }
                val json = gson.toJson(payload)
                val committed = synchronized(persistLock) {
                    val stillLatest = synchronized(this) { mutationGeneration == gen }
                    if (!stillLatest) {
                        false
                    } else {
                        secure.storeSecureValue(STORAGE_KEY, json)
                        synchronized(this) {
                            if (mutationGeneration == gen) {
                                persistedGeneration = gen
                            }
                        }
                        true
                    }
                }
                if (committed) return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
        }
    }

    private fun snapshotLocked(): StorePayload = StorePayload(
        delivered = delivered.toList(),
        locallyRead = locallyRead.toList(),
        readReceiptsSent = readReceiptsSent.toList()
    )

    private data class StorePayload(
        val delivered: List<String> = emptyList(),
        // Keep the existing JSON field name for backward-compatible secure-store migration.
        @SerializedName("read")
        val locallyRead: List<String> = emptyList(),
        @SerializedName("read_receipts_sent")
        val readReceiptsSent: List<String>? = null
    )
}
