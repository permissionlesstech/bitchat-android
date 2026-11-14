package com.bitchat.android.panic

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * A file in `no_backup` that says a panic wipe started and has not been confirmed. It survives
 * the process, so the next start can finish the wipe. [SandboxWipe] keeps it.
 */
class PanicMarker(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, NAME))

    val pending: Boolean get() = file.exists()

    /** Writes the marker and forces it to disk before returning. */
    fun set() {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use {
            it.write(1)
            it.fd.sync()
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val NAME = "panic_pending"
    }
}
