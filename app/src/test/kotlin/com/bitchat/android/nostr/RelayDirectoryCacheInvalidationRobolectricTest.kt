package com.bitchat.android.nostr

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers source metadata, cache deletion and update-timestamp clearing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class RelayDirectoryCacheInvalidationRobolectricTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun prefs() = application.getSharedPreferences("relay_directory_prefs", Context.MODE_PRIVATE)

    private fun cacheFile(content: String = "stub"): File =
        File(application.filesDir, "test_relay_cache.csv").apply { writeText(content) }

    @Test
    fun `a cache with no recorded source url is dropped`() {
        val cache = cacheFile()
        prefs().edit().clear().putLong("last_update_ms", 123L).commit()

        val dropped = RelayDirectory.invalidateCacheIfSourceChanged(prefs(), cache)

        assertTrue(dropped)
        assertFalse(cache.exists())
        assertFalse(prefs().contains("last_update_ms"))
    }

    @Test
    fun `a cache recorded from a different source url is dropped`() {
        val cache = cacheFile()
        prefs().edit().clear()
            .putString("source_url", "https://old.host.example/nostr_relays.csv")
            .putLong("last_update_ms", 123L)
            .commit()

        val dropped = RelayDirectory.invalidateCacheIfSourceChanged(prefs(), cache)

        assertTrue(dropped)
        assertFalse(cache.exists())
    }

    @Test
    fun `a cache recorded from the current source url is kept`() {
        val cache = cacheFile()
        prefs().edit().clear()
            .putString("source_url", RelayDirectory.ASSET_FILE_URL)
            .putLong("last_update_ms", 123L)
            .commit()

        val dropped = RelayDirectory.invalidateCacheIfSourceChanged(prefs(), cache)

        assertFalse(dropped)
        assertTrue(cache.exists())
        assertTrue(prefs().contains("last_update_ms"))
    }

    @Test
    fun `a missing cache changes nothing`() {
        val cache = File(application.filesDir, "absent.csv")
        prefs().edit().clear().putLong("last_update_ms", 123L).commit()

        val dropped = RelayDirectory.invalidateCacheIfSourceChanged(prefs(), cache)

        assertFalse(dropped)
        assertTrue(prefs().contains("last_update_ms"))
    }
}
