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

    private lateinit var store: GeohashBookmarksStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = GeohashBookmarksStore.createForTest(context)
        store.clearAll()
    }

    @Test
    fun `resolved name is stored for a bookmark that is still present`() {
        store.add("u4pruy")

        store.commitResolvedName("u4pruy", "Copenhagen")

        assertEquals("Copenhagen", store.bookmarkNames.value["u4pruy"])
    }

    @Test
    fun `resolved name is dropped when the bookmark was removed while in flight`() {
        store.add("u4pruy")
        store.remove("u4pruy")

        store.commitResolvedName("u4pruy", "Copenhagen")

        assertNull(store.bookmarkNames.value["u4pruy"])
    }

    @Test
    fun `resolved name is dropped when a panic clear happened while in flight`() {
        store.add("u4pruy")
        store.clearAll()

        store.commitResolvedName("u4pruy", "Copenhagen")

        assertNull(store.bookmarkNames.value["u4pruy"])
        assertEquals(emptyMap<String, String>(), store.bookmarkNames.value)
    }

    @Test
    fun `a panic clear survives a restart when a lookup lands after the wipe`() {
        store.add("u4pruy")
        store.clearAll()
        store.commitResolvedName("u4pruy", "Copenhagen")

        // A fresh store reads back what was persisted, which is what the next launch sees.
        val reloaded = GeohashBookmarksStore.createForTest(
            ApplicationProvider.getApplicationContext<Context>()
        )
        assertEquals(emptyList<String>(), reloaded.bookmarks.value)
        assertEquals(emptyMap<String, String>(), reloaded.bookmarkNames.value)
    }

    @Test
    fun `an empty or blank name is never stored`() {
        store.add("u4pruy")

        store.commitResolvedName("u4pruy", null)
        store.commitResolvedName("u4pruy", "")

        assertNull(store.bookmarkNames.value["u4pruy"])
    }
}
