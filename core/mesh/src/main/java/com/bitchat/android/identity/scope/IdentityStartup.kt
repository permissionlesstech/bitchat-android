package com.bitchat.android.identity.scope

/**
 * What a new identity needs before anything uses it: restoring its stored state and starting its
 * background work. Only `:app` binds one; `:wear` binds none, as it never replaces its identity. It
 * runs for each identity after the first, on the main thread, straight after [IdentityHolder.replace].
 * The first identity gets the same two halves from `Application.onCreate`, which calls them itself.
 */
fun interface IdentityStartup {
    fun onIdentityStarted()
}
