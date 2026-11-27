package com.bitchat.android.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * At most one sheet is ever on screen, and a sheet is never under anything.
 * Walks every path that opens a destination from a sheet or over one.
 */
@RunWith(AndroidJUnit4::class)
class NoStackedSheetsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val PEER_A = "0123456789abcdef"
        private val SHEETS = setOf(ChatUserRoute::class, SecurityVerificationRoute::class)

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private fun assertInvariant() {
        rule.waitForIdle()
        val stack = rule.backStack()
        val sheetIndexes = stack.indices.filter { stack[it]::class in SHEETS }
        assertTrue("more than one sheet: $stack", sheetIndexes.size <= 1)
        assertTrue("sheet not on top: $stack", sheetIndexes.all { it == stack.lastIndex })
        assertTrue(rule.onAllNodes(isSheet).fetchSemanticsNodes().size <= 1)
    }

    @Test
    fun peerListToChatToSecurity() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute); assertInvariant()
        rule.runOnUiThread { rule.activity.navigator.openPrivateChat(PEER_A, over = MeshPeerListRoute) }
        assertInvariant()
        rule.navigateTo(SecurityVerificationRoute(PEER_A)); assertInvariant()
    }

    @Test
    fun chatUserToPrivateChat() {
        rule.awaitChat()
        rule.navigateTo(ChatUserRoute("alice", null)); assertInvariant()
        rule.runOnUiThread { rule.activity.navigator.openPrivateChat(PEER_A) }
        assertInvariant()
    }

    @Test
    fun channelsToNotes() {
        rule.awaitChat()
        rule.navigateTo(LocationChannelsRoute); assertInvariant()
        rule.runOnUiThread { rule.activity.navigator.goTo(LocationNotesRoute) }
        assertInvariant()
    }

    @Test
    fun aVerifyLinkNeverLandsOnASheet() {
        rule.awaitChat()
        rule.navigateTo(ChatUserRoute("alice", null))
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("bitchat://verify"))
        rule.runOnUiThread {
            val launchIntent = rule.activity.intent
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(rule.activity, link)
            rule.activity.intent = launchIntent
        }
        rule.waitUntil(5_000) { rule.topOfStack() is VerificationRoute }
        assertInvariant()
    }
}
