package com.bitchat.android.service

/**
 * Makes a destructive application quit irreversible once committed.
 */
internal class ShutdownGate {
    private val lock = Any()
    private var committed = false

    fun commit(): Boolean =
        synchronized(lock) {
            if (committed) {
                false
            } else {
                committed = true
                true
            }
        }

    fun isCommitted(): Boolean = synchronized(lock) { committed }
}
