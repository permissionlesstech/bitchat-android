package com.bitchat.android.net

import kotlinx.coroutines.flow.Flow

/**
 * Whether the app is in the background, which the app binds from its power policy. While it is,
 * [ArtiTorManager] puts a running Arti into soft-dormant mode after a short delay and wakes it
 * on return to the foreground. `:core:tor` has no project dependencies, so it takes this signal
 * through a port instead of reading the mesh's power manager.
 */
interface TorAppBackground {
    val inBackground: Flow<Boolean>
}
