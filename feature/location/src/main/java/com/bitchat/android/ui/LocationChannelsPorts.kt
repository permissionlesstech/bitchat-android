package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The part of [GeohashBookmarksStore] the location channels screen reads and drives. */
interface ChannelBookmarks {
    val bookmarks: StateFlow<List<String>>
    val bookmarkNames: StateFlow<Map<String, String>>
    fun toggle(geohash: String)

    /** Starts a name lookup for [geohash] on the store's own scope, once (Q-L15). */
    fun resolveNameIfNeeded(geohash: String)
}

/** Tor routing as the location channels screen shows and sets it. */
interface TorRouting {
    /** The stored mode, live (R2). */
    val mode: StateFlow<TorMode>
    fun isTorAvailable(): Boolean
    fun setMode(mode: TorMode)
}

internal class StoreChannelBookmarks @Inject constructor(
    private val store: GeohashBookmarksStore,
) : ChannelBookmarks {
    override val bookmarks: StateFlow<List<String>> get() = store.bookmarks
    override val bookmarkNames: StateFlow<Map<String, String>> get() = store.bookmarkNames

    override fun toggle(geohash: String) = store.toggle(geohash)
    override fun resolveNameIfNeeded(geohash: String) = store.resolveNameIfNeeded(geohash)
}

internal class PreferenceTorRouting @Inject constructor(
    @ApplicationContext private val context: Context,
) : TorRouting {
    override val mode: StateFlow<TorMode> get() = TorPreferenceManager.modeFlow

    override fun isTorAvailable(): Boolean = ArtiTorManager.getInstance().isTorAvailable()
    override fun setMode(mode: TorMode) = TorPreferenceManager.set(context, mode)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocationChannelsPortsModule {
    @Binds
    abstract fun bindChannelBookmarks(impl: StoreChannelBookmarks): ChannelBookmarks

    @Binds
    abstract fun bindTorRouting(impl: PreferenceTorRouting): TorRouting
}
