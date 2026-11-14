package com.bitchat.android.panic

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class SandboxWipeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataDir: File = context.dataDir

    private class FakeAliases(initial: List<String>) : KeyAliases {
        private val held = initial.toMutableList()
        override fun aliases(): List<String> = held.toList()
        override fun delete(alias: String) {
            held.remove(alias)
        }
    }

    private fun write(relative: String, text: String = "x"): File =
        File(dataDir, relative).apply {
            parentFile!!.mkdirs()
            writeText(text)
        }

    private fun wipe(aliases: KeyAliases = FakeAliases(emptyList())) =
        SandboxWipe(context, keyStore = aliases).run()

    @Test
    fun wipesEveryUnknownPrefsFileAndDatabaseAndFile() {
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
            .edit().putString("nickname", "anon1234").commit()
        context.getSharedPreferences("geohash_prefs", Context.MODE_PRIVATE)
            .edit().putString("k", "v").commit()
        context.getSharedPreferences("hotspot", Context.MODE_PRIVATE)
            .edit().putString("owned_group_name", "g").commit()
        context.openOrCreateDatabase("private_conversations.db", Context.MODE_PRIVATE, null).close()
        write("files/images/incoming/a.jpg")
        write("cache/x")
        write("no_backup/y")
        write("app_custom/z")

        val result = wipe()

        assertTrue(result.leftovers.toString(), result.confirmed)
        for (path in listOf(
            "shared_prefs/bitchat_prefs.xml",
            "shared_prefs/geohash_prefs.xml",
            "shared_prefs/hotspot.xml",
            "databases/private_conversations.db",
            "files/images",
            "cache/x",
            "no_backup/y",
            "app_custom",
        )) {
            assertFalse(path, File(dataDir, path).exists())
        }
    }

    @Test
    fun keepsListedSettingsAndDropsUnlistedKeys() {
        context.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE).edit()
            .putString("theme_preference", "dark")
            .putString("secret_note", "x")
            .commit()

        val result = wipe()

        val prefs = context.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
        assertEquals("dark", prefs.getString("theme_preference", null))
        assertFalse(prefs.contains("secret_note"))
        assertTrue(result.leftovers.toString(), result.confirmed)
    }

    @Test
    fun keepsKeyPrefixesOfAKeptFile() {
        context.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE).edit()
            .putBoolean("optional_permission_requested_camera", true)
            .putString("other", "x")
            .commit()

        wipe()

        val prefs = context.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("optional_permission_requested_camera", false))
        assertFalse(prefs.contains("other"))
    }

    @Test
    fun keepsKeptPathsWhole() {
        write("files/arti/state")
        write("cache/universal_apk/app.apk")
        write("no_backup/panic_pending")
        write("no_backup/androidx.work.workdb-wal")
        write("files/profileinstaller_x")
        write("files/other")

        val result = wipe()

        for (path in listOf(
            "files/arti/state",
            "cache/universal_apk/app.apk",
            "no_backup/panic_pending",
            "no_backup/androidx.work.workdb-wal",
            "files/profileinstaller_x",
        )) {
            assertTrue(path, File(dataDir, path).exists())
        }
        assertFalse(File(dataDir, "files/other").exists())
        assertTrue(result.leftovers.toString(), result.confirmed)
    }

    @Test
    fun wipesTheFolderOfAFutureGetDirCall() {
        write("app_future/data")
        wipe()
        assertFalse(File(dataDir, "app_future").exists())
    }

    @Test
    fun leavesLibAndCodeCache() {
        write("code_cache/c")
        File(dataDir, "lib").mkdirs()
        val result = wipe()
        assertTrue(File(dataDir, "code_cache/c").exists())
        assertTrue(result.confirmed)
    }

    @Test
    fun wipesExternalFilesAndCache() {
        val ext = context.getExternalFilesDir(null)!!.also { it.mkdirs() }
        File(ext, "m.bin").writeText("x")
        val extCache = context.externalCacheDir!!.also { it.mkdirs() }
        File(extCache, "c.bin").writeText("x")

        val result = wipe()

        assertFalse(File(ext, "m.bin").exists())
        assertFalse(File(extCache, "c.bin").exists())
        assertTrue(result.confirmed)
    }

    @Test
    fun deletesEveryKeystoreAlias() {
        val aliases = FakeAliases(
            listOf("bitchat_conversation_storage_v1", "_androidx_security_master_key_"),
        )

        val result = wipe(aliases)

        assertTrue(aliases.aliases().isEmpty())
        assertEquals(0, result.aliasesLeft)
        assertTrue(result.confirmed)
    }

    @Test
    fun anAliasThatWillNotDeleteIsCounted() {
        val stubborn = object : KeyAliases {
            override fun aliases() = listOf("a")
            override fun delete(alias: String) = throw IllegalStateException("no")
        }

        val result = wipe(stubborn)

        assertEquals(1, result.aliasesLeft)
        assertFalse(result.confirmed)
    }

    @Test
    fun aFileThatWillNotDeleteIsALeftover() {
        val parent = File(dataDir, "files/locked").apply { mkdirs() }
        File(parent, "f").writeText("x")
        assertTrue(parent.setWritable(false))
        try {
            val result = wipe()

            assertFalse(result.confirmed)
            assertTrue(result.leftovers.toString(), result.leftovers.contains("files/locked"))
            assertTrue(result.leftovers.none { File(it).isAbsolute })
        } finally {
            parent.setWritable(true)
        }
    }

    @Test
    fun aPrefsWriterHoldingTheCachedInstanceSeesNothingAfterTheWipe() {
        val held = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
        held.edit().putString("nickname", "anon1234").commit()

        wipe()

        assertNull(held.getString("nickname", null))
    }

    @Test
    fun aSymlinkIsRemovedWithoutFollowingIt() {
        val outside = File(dataDir.parentFile, "outside_target").apply { mkdirs() }
        val inner = File(outside, "keep.txt").apply { writeText("x") }
        try {
            java.nio.file.Files.createSymbolicLink(
                File(dataDir, "files_link").toPath(),
                outside.toPath(),
            )
            val result = wipe()
            assertTrue(inner.exists())
            assertFalse(java.nio.file.Files.isSymbolicLink(File(dataDir, "files_link").toPath()))
            assertTrue(result.confirmed)
        } finally {
            outside.deleteRecursively()
        }
    }
}
