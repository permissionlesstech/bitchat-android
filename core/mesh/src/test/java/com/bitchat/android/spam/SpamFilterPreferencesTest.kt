package com.bitchat.android.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class SpamFilterPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var asked = 0
    private var current = SpamFilter()
    private val currentFilter = Provider { asked++; current }

    @Test
    fun `building the preferences does not touch the filter`() {
        SpamFilterPreferences(currentFilter)

        assertEquals(0, asked)
    }

    @Test
    fun `changing the setting reaches the current spam filter`() {
        val preferences = SpamFilterPreferences(currentFilter)
        val first = current

        preferences.setEnabled(false)
        assertFalse(first.enabled)

        // The identity's filter was replaced: the next change reaches the new one, not the old.
        current = SpamFilter()
        preferences.setDebugBypass(true)
        assertTrue(current.debugBypass)
        assertFalse(first.debugBypass)
    }

    @Test
    fun `a filter built after the setting starts with it`() {
        val preferences = SpamFilterPreferences(currentFilter)
        preferences.init(context)
        preferences.setEnabled(false)
        preferences.setDebugBypass(true)
        val tuned = SpamTuning.DEFAULT.copy(messageBurst = SpamTuning.DEFAULT.messageBurst + 1)
        preferences.setTuning(tuned)

        val fresh = SpamFilter(preferences)

        assertFalse(fresh.enabled)
        assertTrue(fresh.debugBypass)
        assertEquals(tuned.toConfig(), fresh.config)
    }

    @Test
    fun `a restart restores the saved setting into the current filter`() {
        SpamFilterPreferences(currentFilter).apply {
            init(context)
            setEnabled(false)
        }
        current = SpamFilter()

        SpamFilterPreferences(currentFilter).init(context)

        assertFalse(current.enabled)
        assertSame(current, currentFilter.get())
    }
}
