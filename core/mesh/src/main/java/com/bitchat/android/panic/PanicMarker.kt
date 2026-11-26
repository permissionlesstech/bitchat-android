package com.bitchat.android.panic

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * A file in `no_backup` that says a panic wipe started and has not been confirmed. It survives
 * the process, so the next start can finish the wipe. [SandboxWipe] keeps it.
 */
class PanicMarker(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, NAME))

    val pending: Boolean get() = file.exists()

    /**
     * Writes the marker and forces it, and its directory entry, to disk before returning: without
     * the directory a power loss can drop the new file even though its contents were synced.
     */
    fun set() {
        val dir = file.parentFile
        dir?.mkdirs()
        FileOutputStream(file).use {
            it.write(1)
            it.fd.sync()
        }
        dir?.let(::syncDirectory)
    }

    /** Deletes the marker; true only once it is gone. A delete that failed is not a clear. */
    fun clear(): Boolean = file.delete() || !file.exists()

    private fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    companion object {
        const val NAME = "panic_pending"
    }
}
