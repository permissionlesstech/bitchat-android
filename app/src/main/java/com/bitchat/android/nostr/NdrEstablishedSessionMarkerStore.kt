package com.bitchat.android.nostr

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

interface NdrEstablishedSessionMarkerStore {
    fun contains(accountPubkeyHex: String): Boolean
    fun mark(accountPubkeyHex: String)
    fun clearEstablishedSessions()
    fun isPanicWipeRequired(): Boolean
    fun markPanicWipeRequired()
    fun clearPanicWipeRequired()
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
        markerExists(markerFile(accountPubkeyHex))

    override fun mark(accountPubkeyHex: String) {
        check(NdrInputPolicy.isPubkeyHex(accountPubkeyHex))
        publishMarker(markerFile(accountPubkeyHex), "pairwise-v1\n")
    }

    override fun clearEstablishedSessions() {
        if (!directoryExists()) return
        val entries = directory.listFiles()
            ?: throw IOException("Failed to inspect NDR marker directory")
        entries.filter { it.name.endsWith(".established") }
            .forEach { marker ->
                if (!markerExists(marker) ||
                    !marker.delete() ||
                    markerExists(marker)
                ) {
                    throw IOException("Failed to clear NDR downgrade marker")
                }
            }
        syncDirectory()
    }

    override fun isPanicWipeRequired(): Boolean =
        markerExists(panicWipeMarker())

    override fun markPanicWipeRequired() {
        publishMarker(panicWipeMarker(), "panic-wipe-required-v1\n")
    }

    override fun clearPanicWipeRequired() {
        val marker = panicWipeMarker()
        if (markerExists(marker) && (!marker.delete() || markerExists(marker))) {
            throw IOException("Failed to clear NDR panic marker")
        }
        if (directoryExists()) {
            syncDirectory()
        }
    }

    private fun publishMarker(marker: File, content: String) {
        if (!directoryExists() && !directory.mkdirs()) {
            throw IOException("Failed to create NDR marker directory")
        }
        if (markerExists(marker)) return
        val temporary = File(directory, ".${marker.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(marker) || !markerExists(marker)) {
            temporary.delete()
            throw IOException("Failed to publish NDR marker")
        }
        syncDirectory()
    }

    private fun directoryExists(): Boolean =
        readAttributesOrNull(directory)?.let { attributes ->
            if (!attributes.isDirectory) {
                throw IOException("NDR marker path is not a directory")
            }
            true
        } ?: false

    private fun markerExists(marker: File): Boolean =
        readAttributesOrNull(marker)?.let { attributes ->
            if (!attributes.isRegularFile) {
                throw IOException("NDR marker path is not a regular file")
            }
            true
        } ?: false

    private fun readAttributesOrNull(file: File): BasicFileAttributes? =
        try {
            Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
        } catch (_: NoSuchFileException) {
            null
        }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    private fun markerFile(accountPubkeyHex: String): File =
        File(directory, "${accountPubkeyHex.lowercase()}.established")

    private fun panicWipeMarker(): File =
        File(directory, "panic-wipe-required")
}
