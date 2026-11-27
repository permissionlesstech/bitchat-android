package com.bitchat.android.panic

/**
 * Starts the app again in a new process. A panic uses it only when the wipe or a teardown cleanup
 * cannot confirm, with the panic marker still set, so the next launch finishes the wipe before
 * anything reads storage. It does not return in the app.
 */
fun interface ProcessRestarter {
    fun restart()
}
