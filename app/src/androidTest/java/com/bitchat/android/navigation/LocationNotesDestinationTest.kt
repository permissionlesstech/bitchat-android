package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Location notes as a full-screen destination.
 *
 * Without location there are no notes to show, so what is on screen is the
 * unavailable state; it has to be a screen too. Navigation only is asserted.
 */
@RunWith(AndroidJUnit4::class)
class LocationNotesDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun locationNotesOpenAsAScreenAndCloseWithBack() {
        rule.awaitChat()
        rule.navigateTo(LocationNotesRoute)
        rule.waitUntil(5_000) { rule.topOfStack() == LocationNotesRoute }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(isSheet).fetchSemanticsNodes().size)

        rule.onNodeWithContentDescription(rule.activity.getString(R.string.back)).performClick()

        rule.waitUntil(5_000) { rule.topOfStack() == ChatRoute }
    }
}
