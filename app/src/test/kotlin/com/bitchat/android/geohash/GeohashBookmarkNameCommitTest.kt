package com.bitchat.android.geohash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A reverse geocode started for a bookmark runs on the IO dispatcher and can finish after
 * the bookmark is gone. These tests pin the commit step: a resolved name is stored only
 * while the geohash is still bookmarked.
 */
@RunWith(RobolectricTestRunner::class)
class GeohashBookmarkNameCommitTest {

    // Artificial all-zero geohash and label; neither comes from a device or user location.

    private lateinit var store: GeohashBookmarksStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = GeohashBookmarksStore.createForTest(context)
        store.clearAll()
    }

    @Test
    fun `resolved name is stored for a bookmark that is still present`() {
        store.add("000000")

        store.commitResolvedName("000000", "Synthetic bookmark A")

        assertEquals("Synthetic bookmark A", store.bookmarkNames.value["000000"])
    }

    @Test
    fun `resolved name is dropped when the bookmark was removed while in flight`() {
        store.add("000000")
        store.remove("000000")

        store.commitResolvedName("000000", "Synthetic bookmark A")

        assertNull(store.bookmarkNames.value["000000"])
    }

    @Test
    fun `resolved name is dropped when a panic clear happened while in flight`() {
        store.add("000000")
        store.clearAll()

        store.commitResolvedName("000000", "Synthetic bookmark A")

        assertNull(store.bookmarkNames.value["000000"])
        assertEquals(emptyMap<String, String>(), store.bookmarkNames.value)
    }

    @Test
    fun `a panic clear survives a restart when a lookup lands after the wipe`() {
        store.add("000000")
        store.clearAll()
        store.commitResolvedName("000000", "Synthetic bookmark A")

        // A fresh store reads back what was persisted, which is what the next launch sees.
        val reloaded = GeohashBookmarksStore.createForTest(
            ApplicationProvider.getApplicationContext<Context>()
        )
        assertEquals(emptyList<String>(), reloaded.bookmarks.value)
        assertEquals(emptyMap<String, String>(), reloaded.bookmarkNames.value)
    }

    @Test
    fun `an empty or blank name is never stored`() {
        store.add("000000")

        store.commitResolvedName("000000", null)
        store.commitResolvedName("000000", "")

        assertNull(store.bookmarkNames.value["000000"])
    }
}
