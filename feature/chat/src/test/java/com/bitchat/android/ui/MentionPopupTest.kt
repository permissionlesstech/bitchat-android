package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The mention popup's height; the suggestions themselves are tested in :core:chat. */
class MentionPopupTest {

    @Test
    fun `mention popup viewport is capped at five rows`() {
        assertEquals(5, MaxVisibleMentionSuggestions)
    }
}
