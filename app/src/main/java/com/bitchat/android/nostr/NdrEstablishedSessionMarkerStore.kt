package com.bitchat.android.nostr

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

interface NdrEstablishedSessionMarkerStore {
    fun contains(accountPubkeyHex: String): Boolean
    fun mark(accountPubkeyHex: String)
    fun clearAll()
}

/**
 * A downgrade marker intentionally stored outside the ratchet database tree.
 *
 * If the database is later missing while this marker remains, the host must
 * fail closed instead of silently creating a fresh no-session runtime.
 */
internal class FileNdrEstablishedSessionMarkerStore(
    private val directory: File
) : NdrEstablishedSessionMarkerStore {
    override fun contains(accountPubkeyHex: String): Boolean =
        markerFile(accountPubkeyHex).isFile

    override fun mark(accountPubkeyHex: String) {
        check(NdrInputPolicy.isPubkeyHex(accountPubkeyHex))
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create NDR marker directory")
        }
        val marker = markerFile(accountPubkeyHex)
        if (marker.isFile) return
        val temporary = File(directory, ".${marker.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write("pairwise-v1\n".toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(marker)) {
            temporary.delete()
            throw IOException("Failed to publish NDR downgrade marker")
        }
    }

    override fun clearAll() {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Failed to clear NDR downgrade markers")
        }
    }

    private fun markerFile(accountPubkeyHex: String): File =
        File(directory, "${accountPubkeyHex.lowercase()}.established")
}
