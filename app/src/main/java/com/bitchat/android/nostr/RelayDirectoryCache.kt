package com.bitchat.android.nostr

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

internal object RelayDirectoryCache {
    fun replace(input: InputStream, destination: File) {
        // Stage beside the destination to keep the rename on the same filesystem.
        val staged = File.createTempFile("relays_install_", ".csv", destination.absoluteFile.parentFile)
        try {
            staged.outputStream().use { output -> input.copyTo(output) }
            Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staged.toPath())
        }
    }
}
