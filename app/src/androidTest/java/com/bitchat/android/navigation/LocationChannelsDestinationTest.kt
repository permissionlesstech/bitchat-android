package com.bitchat.android.navigation

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Location channels as a full-screen destination.
 *
 * Asserts navigation and layout only. The screen lists location channels, and
 * nothing here reads or records what they are.
 */
@RunWith(AndroidJUnit4::class)
class LocationChannelsDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun backFromChannelsReturnsToChat() {
        rule.awaitChat()
        rule.navigateTo(LocationChannelsRoute)
        awaitChannels()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        assertFalse(rule.activity.isFinishing)
    }

    /**
     * A sheet applied the status bar inset to everything in it; a destination
     * has to apply it itself. The top bar insets itself either way, so what
     * shows a missing inset is the list: its first card slides under the bar.
     */
    @Test
    fun theFirstCardStartsBelowTheTopBar() {
        rule.awaitChat()
        rule.navigateTo(LocationChannelsRoute)
        awaitChannels()

        val closeBottom = rule.onNodeWithContentDescription(rule.activity.getString(R.string.back))
            .getUnclippedBoundsInRoot().bottom
        val firstCardTop = rule.onAllNodesWithText(rule.activity.getString(R.string.mesh_title))
            .onFirst()
            .getUnclippedBoundsInRoot().top

        assertTrue("first card at $firstCardTop, top bar content ends at $closeBottom", firstCardTop >= closeBottom)
    }

    /**
     * Notes replaces channels rather than stacking on it, so Back from notes
     * returns to chat as it did when both were sheets.
     */
    @Test
    fun notesOpenedFromChannelsCloseBackToChat() {
        rule.awaitChat()
        rule.navigateTo(LocationChannelsRoute)
        awaitChannels()
        val notes = rule.activity.getString(R.string.cd_location_notes)

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(notes))
        rule.onNodeWithText(notes).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == LocationNotesRoute }

        assertEquals(listOf(ChatRoute, LocationNotesRoute), rule.backStack())

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        assertFalse(rule.activity.isFinishing)
    }

    private fun awaitChannels() {
        val title = rule.activity.getString(R.string.location_channels_title)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(title, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }
}
