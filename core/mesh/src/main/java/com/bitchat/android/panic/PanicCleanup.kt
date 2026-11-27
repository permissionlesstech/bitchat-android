package com.bitchat.android.panic

/**
 * One effect of the app that lives outside its sandbox (the notification shade, the clipboard, an
 * in-memory debug buffer), which a panic has to undo too. Implementations are multibound into a
 * set that the panic runs; a new one is found by [name] in the failure it reports.
 */
interface PanicCleanup {
    val name: String

    /**
     * Undoes the effect. Returns true only when it is confirmed gone; false or an exception is a
     * failure the panic reports. A cleanup never swallows its own failure into true.
     */
    fun run(): Boolean
}
