package com.bitchat.android.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class FileNdrEstablishedSessionMarkerStoreTest {
    private val accountPubkey = "ab".repeat(32)

    @Test
    fun establishedAndPanicMarkersHaveIndependentDurableLifetimes() {
        val directory = Files.createTempDirectory("ndr-marker-store")
            .resolve("markers")
            .toFile()
        val store = FileNdrEstablishedSessionMarkerStore(directory)

        assertFalse(store.contains(accountPubkey))
        assertFalse(store.isPanicWipeRequired())

        store.mark(accountPubkey)
        store.markPanicWipeRequired()
        assertTrue(store.contains(accountPubkey))
        assertTrue(store.isPanicWipeRequired())

        store.clearEstablishedSessions()
        assertFalse(store.contains(accountPubkey))
        assertTrue(store.isPanicWipeRequired())

        store.clearPanicWipeRequired()
        assertFalse(store.isPanicWipeRequired())
    }

    @Test
    fun unreadableMarkerDirectoryShapeFailsClosed() {
        val directory = Files.createTempDirectory("ndr-marker-invalid")
            .resolve("markers")
            .toFile()
        directory.writeText("not-a-directory")
        val store = FileNdrEstablishedSessionMarkerStore(directory)

        assertThrows(IOException::class.java) {
            store.contains(accountPubkey)
        }
        assertThrows(IOException::class.java) {
            store.isPanicWipeRequired()
        }
        assertThrows(IOException::class.java) {
            store.clearEstablishedSessions()
        }
    }
}
