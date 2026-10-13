package com.bitchat.android.navigation

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every full screen closes with a leading back arrow, never a trailing close. */
@RunWith(AndroidJUnit4::class)
class ScreenChromeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private fun string(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun assertBackArrowReturnsToChat(route: NavKey) {
        rule.awaitChat()
        rule.navigateTo(route)
        rule.waitUntil(5_000) { rule.topOfStack() == route }
        assertEquals(
            0,
            rule.onAllNodesWithContentDescription(string(R.string.close_plain))
                .fetchSemanticsNodes().size
        )
        rule.onNodeWithContentDescription(string(R.string.back)).performClick()
        rule.waitUntil(5_000) { rule.topOfStack() == ChatRoute }
    }

    @Test fun aboutClosesWithABackArrow() = assertBackArrowReturnsToChat(AboutRoute)
    @Test fun locationChannelsCloseWithABackArrow() = assertBackArrowReturnsToChat(LocationChannelsRoute)
    @Test fun verificationClosesWithABackArrow() = assertBackArrowReturnsToChat(VerificationRoute(null))
    @Test fun debugSettingsClosesWithABackArrow() = assertBackArrowReturnsToChat(DebugSettingsRoute)

    /** The back arrow sits below the status bar, where it can be tapped. */
    @Test
    fun aboutsBackArrowClearsTheStatusBar() = assertBackArrowClearsTheStatusBar(AboutRoute)

    @Test
    fun debugSettingsBackArrowClearsTheStatusBar() = assertBackArrowClearsTheStatusBar(DebugSettingsRoute)

    /**
     * About's list starts below the status bar. The top bar insets itself, so
     * the arrow test above passes even when the screen drops its own inset
     * padding; the list does not, and its first item slides up under the bar.
     * Measured on the list rather than on its first item: the hero's first
     * node with semantics, the title, sits far enough down the item to clear
     * the bar either way.
     */
    @Test
    fun aboutsContentClearsTheStatusBar() {
        rule.awaitChat()
        rule.navigateTo(AboutRoute)
        rule.waitUntil(5_000) { rule.topOfStack() == AboutRoute }
        rule.waitForIdle()

        val statusBarBottom = androidx.core.view.ViewCompat
            .getRootWindowInsets(rule.activity.window.decorView)!!
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
        val listTop = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInWindow.top

        assertTrue("About's list at $listTop starts under the status bar ($statusBarBottom)", listTop >= statusBarBottom)
    }

    private fun assertBackArrowClearsTheStatusBar(route: NavKey) {
        rule.awaitChat()
        rule.navigateTo(route)
        rule.waitUntil(5_000) { rule.topOfStack() == route }
        rule.waitForIdle()
        val statusBarTop = androidx.core.view.ViewCompat
            .getRootWindowInsets(rule.activity.window.decorView)!!
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
        val arrowTop = rule.onNodeWithContentDescription(string(R.string.back))
            .fetchSemanticsNode().boundsInWindow.top
        assertTrue("back arrow at $arrowTop is under the status bar ($statusBarTop)", arrowTop >= statusBarTop)
    }
}
