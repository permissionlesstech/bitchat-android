package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager

internal object NdrPanicStartupRecovery {
    private const val TAG = "NdrPanicStartup"

    @Volatile
    private var networkStartupAllowed = true

    fun recoverBeforeNetwork(context: Context): Boolean {
        val filesDirectory = context.filesDir
        val markerStore = FileNdrEstablishedSessionMarkerStore(
            filesDirectory.resolve("ndr-established-sessions")
        )
        val panicStorageQuarantine = FileNdrPanicStorageQuarantine(
            filesDirectory.resolve("ndr")
        )
        return recoverBeforeNetwork(
            markerStore = markerStore,
            panicStorageQuarantine = panicStorageQuarantine,
            clearIdentity = {
                SecureIdentityStateManager(context.applicationContext)
                    .clearIdentityData()
            },
            clearFavorites = {
                FavoritesPersistenceService.initialize(context.applicationContext)
                FavoritesPersistenceService.shared.clearAllFavoritesAfterNdrReset()
            }
        )
    }

    internal fun recoverBeforeNetwork(
        markerStore: NdrEstablishedSessionMarkerStore,
        panicStorageQuarantine: NdrPanicStorageQuarantine,
        clearIdentity: () -> Boolean,
        clearFavorites: () -> Boolean
    ): Boolean {
        val retryRequired = runCatching {
            val markerRetryRequired = markerStore.isPanicWipeRequired()
            val quarantineRetryRequired = panicStorageQuarantine.isPending()
            markerRetryRequired || quarantineRetryRequired
        }.getOrElse {
            networkStartupAllowed = false
            return false
        }
        if (!retryRequired) {
            networkStartupAllowed = true
            return true
        }

        val recovered = runCatching {
            panicStorageQuarantine.begin()
            panicStorageQuarantine.wipeNativeState()
            markerStore.clearEstablishedSessions()
            check(clearIdentity()) { "Failed to clear host identity state" }
            check(clearFavorites()) { "Failed to clear NDR contact protection state" }
            markerStore.clearPanicWipeRequired()
            panicStorageQuarantine.clear()
        }.onFailure {
            Log.e(TAG, "Blocking network startup until panic wipe retry succeeds")
        }.isSuccess
        networkStartupAllowed = recovered
        return recovered
    }

    fun isNetworkStartupAllowed(): Boolean = networkStartupAllowed

    fun blockNetworkStartup() {
        networkStartupAllowed = false
    }
}
