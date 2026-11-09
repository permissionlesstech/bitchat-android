package com.bitchat.android.identity.scope

/**
 * What a new identity needs before anything uses it: restoring its stored state and starting its
 * background work. Each client binds its own. It runs for every identity, on the main thread: at
 * process start for the first, and straight after [IdentityHolder.replace] for each one after it.
 */
fun interface IdentityStartup {
    fun onIdentityStarted()
}
