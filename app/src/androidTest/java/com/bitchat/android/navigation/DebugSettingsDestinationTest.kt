package com.bitchat.android.navigation

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.testhook.TestHookDependencies
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug settings, a destination stacked on About: the pair the spec asks to
 * see unwind one at a time.
 */
@RunWith(AndroidJUnit4::class)
class DebugSettingsDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun backFromDebugReturnsToAboutAndThenToChat() {
        openDebugOverAbout()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == AboutRoute }
        rule.awaitAbout()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        assertFalse(rule.activity.isFinishing)
    }

    /** Two quick presses pop two destinations, one each, and leave the app open. */
    @Test
    fun aQuickDoubleBackFromDebugLandsOnChat() {
        openDebugOverAbout()

        rule.pressSystemBack(times = 2)

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitForIdle()
        assertEquals(listOf(ChatRoute), rule.backStack())
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun theListStartsBelowTheTopBar() {
        openDebugOverAbout()

        val closeBottom = rule.onNodeWithContentDescription(rule.activity.getString(R.string.back))
            .getUnclippedBoundsInRoot().bottom
        val firstItemTop = rule.onAllNodesWithText(rule.activity.getString(R.string.debug_tools_desc))
            .onFirst()
            .getUnclippedBoundsInRoot().top

        assertTrue("first item at $firstItemTop, top bar content ends at $closeBottom", firstItemTop >= closeBottom)
    }

    /**
     * Through the real entry point. About leaves composition while Debug is
     * open, where a sheet kept it composed, so the tab has to be saved to
     * survive the round trip.
     */
    @Test
    fun backFromDebugReturnsToTheSettingsTabItWasOpenedFrom() {
        rule.awaitChat()
        rule.openAbout()
        val debugButton = rule.activity.getString(R.string.about_debug_settings)
        rule.onAllNodesWithText(rule.activity.getString(R.string.about_tab_settings), ignoreCase = true)
            .onFirst()
            .performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(debugButton))
        rule.onNodeWithText(debugButton).performClick()
        awaitDebug()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == AboutRoute }
        // Not awaitAbout: its marker is the tab row, and About comes back scrolled to where
        // Debug was opened, with the tabs off screen.
        rule.waitForIdle()
        // The Debug button exists only on the Settings tab.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(debugButton))
        rule.onNodeWithText(debugButton).assertExists()
    }

    /**
     * Debug settings goes idle with nodes in the mesh graph. The device's own
     * announcement puts its node there a while after the mesh starts, and the
     * graph used to step every frame forever, so a test that opened Debug
     * after that point never saw Compose go idle. Synthetic nodes stand in
     * for the announcement so this does not depend on timing.
     */
    @Test
    fun theScreenGoesIdleWithAMeshGraphShowing() {
        val graph = EntryPointAccessors.fromApplication(
            rule.activity.applicationContext,
            TestHookDependencies::class.java,
        ).meshGraphService()
        graph.updateFromAnnouncement(
            "0123456789abcdef", "alice", listOf("fedcba9876543210"),
            System.currentTimeMillis().toULong(),
        )
        try {
            openDebugOverAbout()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Mesh topology"))
            // The graph's own peer list proves the graph, not "No gossip yet", is shown.
            rule.waitUntil(timeoutMillis = 5_000) {
                rule.onAllNodesWithText("01234567 • alice").fetchSemanticsNodes().isNotEmpty()
            }

            // Throws ComposeNotIdleException while the graph keeps stepping.
            rule.waitForIdle()
        } finally {
            graph.removePeer("0123456789abcdef")
            graph.removePeer("fedcba9876543210")
        }
    }

    private fun openDebugOverAbout() {
        rule.awaitChat()
        rule.navigateTo(AboutRoute)
        rule.awaitAbout()
        rule.navigateTo(DebugSettingsRoute)
        awaitDebug()
        assertEquals(listOf(ChatRoute, AboutRoute, DebugSettingsRoute), rule.backStack())
    }

    private fun awaitDebug() {
        val title = rule.activity.getString(R.string.debug_tools)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(title, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }
}
