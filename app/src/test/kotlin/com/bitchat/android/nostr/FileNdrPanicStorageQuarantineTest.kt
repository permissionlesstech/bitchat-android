package com.bitchat.android.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileNdrPanicStorageQuarantineTest {
    @Test
    fun activeStateIsRenamedBeforeWipeAndResidueSurvivesRestartUntilCompletion() {
        val parent = Files.createTempDirectory("ndr-panic-quarantine").toFile()
        val storage = parent.resolve("ndr").apply {
            resolve("pairwise-v1/account/state").apply {
                parentFile.mkdirs()
                writeText("sensitive")
            }
        }
        val quarantineDirectory = parent.resolve("quarantine")
        val quarantine = FileNdrPanicStorageQuarantine(storage, quarantineDirectory)

        quarantine.begin()

        assertFalse(storage.exists())
        assertTrue(quarantineDirectory.resolve("pairwise-v1/account/state").isFile)
        assertTrue(
            FileNdrPanicStorageQuarantine(storage, quarantineDirectory).isPending()
        )

        quarantine.wipeNativeState()

        assertTrue(quarantine.isPending())
        assertTrue(quarantineDirectory.listFiles()?.isEmpty() == true)

        quarantine.clear()

        assertFalse(quarantine.isPending())
        assertFalse(quarantineDirectory.exists())
    }

    @Test
    fun retryWipesAnyActiveStateCreatedBesideExistingQuarantine() {
        val parent = Files.createTempDirectory("ndr-panic-retry").toFile()
        val storage = parent.resolve("ndr")
        val quarantineDirectory = parent.resolve("quarantine").apply { mkdirs() }
        storage.resolve("late/state").apply {
            parentFile.mkdirs()
            writeText("sensitive")
        }
        quarantineDirectory.resolve("old/state").apply {
            parentFile.mkdirs()
            writeText("sensitive")
        }
        val quarantine = FileNdrPanicStorageQuarantine(storage, quarantineDirectory)

        quarantine.begin()
        quarantine.wipeNativeState()

        assertFalse(storage.exists())
        assertTrue(quarantineDirectory.listFiles()?.isEmpty() == true)
        assertTrue(quarantine.isPending())
    }
}
