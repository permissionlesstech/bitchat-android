package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.bitchat.android.di.IoDispatcher
import com.bitchat.android.identity.scope.IdentityScoped
import com.bitchat.android.identity.scope.IdentityTeardown
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@IdentityScoped
class SeenMessageStore internal constructor(
    context: Context,
    private val appStateStore: AppStateStore,
    private val persistScope: CoroutineScope,
    private val persistDelayMs: Long = DEFAULT_PERSIST_DELAY_MS,
    private val payloadSerializer: ((Any) -> String)? = null,
) {
    /**
     * Writes run one at a time on the IO dispatcher, in a scope of this identity's own. Ending the
     * identity cancels it and [close]s the store, so a batched write never lands after the wipe.
     * The defaults are for tests that build a store outside an identity.
     */
    @Inject constructor(
        @ApplicationContext context: Context,
        appStateStore: AppStateStore,
        @IoDispatcher ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        teardown: IdentityTeardown = IdentityTeardown(),
    ) : this(
        context = context,
        appStateStore = appStateStore,
        persistScope = CoroutineScope(ioDispatcher.limitedParallelism(1) + SupervisorJob()),
    ) {
        teardown.register("SeenMessageStore") {
            close()
            persistScope.cancel()
        }
    }

    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val DEFAULT_PERSIST_DELAY_MS = 500L
        private const val MAX_IDS = com.bitchat.android.util.AppConstants.Services.SEEN_MESSAGE_MAX_IDS
    }

    private val gson = Gson()
    private val secure = SecureIdentityStateManager(context)
    private val persistLock = Any()
    private var pendingPersistJob: Job? = null
    private var mutationGeneration: Long = 0L
    private var destructiveGeneration: Long = 0L
    private var persistedGeneration: Long = 0L
    private var closed = false

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
        appStateStore.markPrivateMessageRead(id)
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
                destructiveGeneration++
                pendingPersistJob?.cancel()
                pendingPersistJob = null
            }
            modified
        }
        if (!changed) return
        // Enqueue immediate serialized IO write so UI conversation deletion never blocks the main thread.
        persistScope.launch {
            persistOnce()
            synchronized(this@SeenMessageStore) {
                if (persistedGeneration < mutationGeneration) {
                    schedulePersistLocked()
                }
            }
        }
    }

    /**
     * Ends the store with its identity: cancels the batched write and waits for one in progress, so
     * nothing is written once this returns. The panic then wipes the sandbox, which takes the
     * stored IDs with it.
     */
    fun close() {
        synchronized(this) {
            closed = true
            pendingPersistJob?.cancel()
            pendingPersistJob = null
        }
        // Taking the lock waits out a write already past its check.
        synchronized(persistLock) { }
    }

    /**
     * Bounded-latency batching: never cancel/restart an active timer, so continuous
     * mutations cannot starve persistence indefinitely.
     */
    private fun schedulePersistLocked() {
        if (closed || pendingPersistJob?.isActive == true) return
        pendingPersistJob = persistScope.launch {
            delay(persistDelayMs)
            persistOnce()
            // If more mutations arrived while persistOnce() ran, schedule one follow-up flush.
            synchronized(this@SeenMessageStore) {
                pendingPersistJob = null
                if (persistedGeneration < mutationGeneration) {
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
     * Perform a single snapshot + serialize + persist pass without unbounded retry churn.
     *
     * Concurrent additive mutations during serialization are safe to persist incrementally (the
     * next bounded batch persists newer IDs). Only destructive mutations (`remove`) bump
     * [destructiveGeneration] and abort an older snapshot so deleted/wiped IDs are never
     * resurrected on disk.
     */
    private fun persistOnce(): Boolean {
        return try {
            val (gen, destructiveGen, payload) = synchronized(this) {
                Triple(mutationGeneration, destructiveGeneration, snapshotLocked())
            }
            val json = payloadSerializer?.invoke(payload) ?: gson.toJson(payload)
            synchronized(persistLock) {
                val safeToCommit = synchronized(this) { !closed && destructiveGeneration == destructiveGen }
                if (!safeToCommit) {
                    false
                } else {
                    secure.storeSecureValue(STORAGE_KEY, json)
                    synchronized(this) {
                        if (destructiveGeneration == destructiveGen && gen > persistedGeneration) {
                            persistedGeneration = gen
                        }
                    }
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
            false
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
