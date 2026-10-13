package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Verification as a full-screen destination, pushed over the peer list when
 * opened from it.
 */
@RunWith(AndroidJUnit4::class)
class VerificationDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun backFromVerificationOpenedFromThePeerListReturnsToTheList() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.navigateTo(VerificationRoute(peerID = null))
        awaitVerification()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == MeshPeerListRoute }
        rule.awaitPeerList()
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun backFromVerificationOpenedElsewhereReturnsToChat() {
        rule.awaitChat()
        rule.navigateTo(VerificationRoute(peerID = null))
        awaitVerification()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        assertEquals(listOf(ChatRoute), rule.backStack())
        assertFalse(rule.activity.isFinishing)
    }

    /** The second press must not pop what lies beneath, let alone leave. */
    @Test
    fun aQuickDoubleBackOnVerificationStaysInTheApp() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.navigateTo(VerificationRoute(peerID = null))
        awaitVerification()

        rule.pressSystemBack(times = 2)

        rule.waitUntil(timeoutMillis = 5_000) { VerificationRoute(null) !in rule.backStack() }
        rule.waitForIdle()
        assertTrue(rule.backStack().first() == ChatRoute)
        assertFalse(rule.activity.isFinishing)
    }

    private fun awaitVerification() {
        val title = rule.activity.getString(R.string.verify_title)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(title, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }
}
