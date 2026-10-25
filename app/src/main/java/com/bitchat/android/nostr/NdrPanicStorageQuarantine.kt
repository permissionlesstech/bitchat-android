package com.bitchat.android.nostr

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A durable panic-wipe latch that is independent of the NDR marker store.
 *
 * The active NDR directory is renamed out of its normal path before any
 * destructive work. The empty quarantine directory remains as retry evidence
 * until native state, host identity, and contact protection state are all
 * durably gone.
 */
interface NdrPanicStorageQuarantine {
    fun isPending(): Boolean
    fun begin()
    fun wipeNativeState()
    fun clear()
}

internal class FileNdrPanicStorageQuarantine(
    private val storageDirectory: File,
    private val quarantineDirectory: File =
        requireNotNull(storageDirectory.parentFile).resolve("ndr-panic-quarantine-v1")
) : NdrPanicStorageQuarantine {
    private val parentDirectory: File =
        requireNotNull(storageDirectory.parentFile)

    override fun isPending(): Boolean =
        directoryExists(quarantineDirectory, "NDR panic quarantine")

    override fun begin() {
        if (!directoryExists(parentDirectory, "NDR storage parent")) {
            throw IOException("NDR storage parent is unavailable")
        }
        if (isPending()) {
            syncDirectory(parentDirectory)
            return
        }

        val latchCreated = if (
            directoryExists(storageDirectory, "Active NDR storage")
        ) {
            storageDirectory.renameTo(quarantineDirectory)
        } else {
            quarantineDirectory.mkdir()
        }
        if (!latchCreated || !isPending()) {
            throw IOException("Failed to establish NDR panic quarantine")
        }
        syncDirectory(parentDirectory)
    }

    override fun wipeNativeState() {
        if (!isPending()) {
            throw IOException("NDR panic quarantine is not established")
        }

        val quarantinedEntries = quarantineDirectory.listFiles()
            ?: throw IOException("Failed to inspect NDR panic quarantine")
        quarantinedEntries.forEach { entry ->
            if (!entry.deleteRecursively() || pathExists(entry)) {
                throw IOException("Failed to wipe quarantined NDR state")
            }
        }
        syncDirectory(quarantineDirectory)

        if (pathExists(storageDirectory) &&
            (!storageDirectory.deleteRecursively() || pathExists(storageDirectory))
        ) {
            throw IOException("Failed to wipe active NDR state during panic")
        }
        syncDirectory(parentDirectory)
    }

    override fun clear() {
        if (!isPending()) return
        if (pathExists(storageDirectory)) {
            throw IOException("Active NDR state exists while completing panic wipe")
        }
        val remaining = quarantineDirectory.listFiles()
            ?: throw IOException("Failed to inspect NDR panic quarantine")
        if (remaining.isNotEmpty()) {
            throw IOException("Quarantined NDR state remains")
        }
        if (!quarantineDirectory.delete() || pathExists(quarantineDirectory)) {
            throw IOException("Failed to clear NDR panic quarantine")
        }
        syncDirectory(parentDirectory)
    }

    private fun directoryExists(file: File, description: String): Boolean =
        readAttributesOrNull(file)?.let { attributes ->
            if (!attributes.isDirectory) {
                throw IOException("$description is not a directory")
            }
            true
        } ?: false

    private fun pathExists(file: File): Boolean =
        readAttributesOrNull(file) != null

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

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}
