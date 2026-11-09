package com.bitchat.android.di

import com.bitchat.android.identity.scope.IdentityTeardown
import javax.inject.Inject

/**
 * The cleanups a session's classes registered for what lives outside its coroutine scope. One per
 * session; [runAll] runs them when the session ends, which can be long before the identity ends.
 * It behaves as [IdentityTeardown] does, which it wraps.
 */
@ChatSessionScoped
class ChatSessionTeardown @Inject constructor() {
    private val cleanups = IdentityTeardown()

    /**
     * Registers [cleanup] to run when the session ends. Cleanups run newest first.
     *
     * A cleanup runs while the session holder's lock is held, with the session already forgotten, so
     * it must not read `ChatSessionHolder` or a `@CurrentSession` binding: that would build another
     * session. Capture what it needs when it is registered.
     *
     * Registering after the session ended runs [cleanup] at once: a late reader can still build a
     * class of an ended session.
     */
    fun register(name: String, cleanup: () -> Unit) = cleanups.register(name, cleanup)

    /** Runs every cleanup, newest first, and returns the names of those that threw. Runs once. */
    fun runAll(): List<String> = cleanups.runAll()
}
