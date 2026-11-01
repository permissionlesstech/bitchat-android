package com.bitchat.android.testhook

import android.content.Context
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.spam.SpamFilter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The app graph's shared mesh stores, for device tests that check each is built once and that the
 * mesh and the panic wipe hold the same ones. Debug builds only.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MeshTestAccess {
    fun appStateStore(): AppStateStore
    fun contactDirectory(): ContactDirectory
    fun transportBridgeService(): TransportBridgeService
    fun favorites(): FavoritesPersistenceService
    fun spamFilter(): SpamFilter
    fun seenMessageStore(): SeenMessageStore
    fun meshServiceHolder(): MeshServiceHolder

    companion object {
        fun of(context: Context): MeshTestAccess =
            EntryPointAccessors.fromApplication(context, MeshTestAccess::class.java)
    }
}
