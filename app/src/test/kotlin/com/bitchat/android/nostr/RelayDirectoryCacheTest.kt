package com.bitchat.android.nostr

import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelayDirectoryCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `failed staging keeps the previous cache and removes staging files`() {
        val destination = temporary.newFile("directory.csv").apply { writeText("previous") }
        val input = object : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ < 4) 'x'.code else throw IOException("synthetic read failure")
        }
        assertThrows(IOException::class.java) { input.use { RelayDirectoryCache.replace(it, destination) } }
        assertEquals("previous", destination.readText())
        assertEquals(listOf("directory.csv"), temporary.root.list()!!.sorted())
    }

    @Test
    fun `successful staging replaces the existing cache`() {
        val destination = temporary.newFile("directory.csv").apply { writeText("previous") }
        "replacement".byteInputStream().use { RelayDirectoryCache.replace(it, destination) }
        assertEquals("replacement", destination.readText())
        assertEquals(listOf("directory.csv"), temporary.root.list()!!.sorted())
    }

    @Test
    fun `failed rename keeps the destination directory and removes staging files`() {
        val destination = temporary.newFolder("directory.csv")
        File(destination, "keep").writeText("previous")
        assertThrows(IOException::class.java) {
            "replacement".byteInputStream().use { RelayDirectoryCache.replace(it, destination) }
        }
        assertEquals("previous", File(destination, "keep").readText())
        assertEquals(listOf("directory.csv"), temporary.root.list()!!.sorted())
    }
}
