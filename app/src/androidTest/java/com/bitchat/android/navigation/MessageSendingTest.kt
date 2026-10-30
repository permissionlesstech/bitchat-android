package com.bitchat.android.navigation

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.testhook.ChatSessionTestAccess
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's send path, wired end to end through the session graph.
 *
 * The emulator has no peers, so nothing leaves the device; what is checked is
 * that the send reaches the right timeline and reports back to the composer.
 * The text is synthetic.
 */
@RunWith(AndroidJUnit4::class)
class MessageSendingTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun aPublicMessageLandsInTheMeshTimeline() {
        rule.awaitChat()
        var accepted: Boolean? = null

        rule.runOnUiThread {
            session().messageSender().send("synthetic mesh message") { accepted = it }
        }

        rule.waitUntil(timeoutMillis = 5_000) { accepted != null }
        assertEquals(true, accepted)
        rule.waitUntil(timeoutMillis = 5_000) {
            timeline().any { it == "synthetic mesh message" }
        }
    }

    @Test
    fun aCommandRunsInsteadOfBeingSent() {
        rule.awaitChat()

        rule.runOnUiThread { session().messageSender().send("/w") {} }

        rule.waitUntil(timeoutMillis = 5_000) {
            timeline().any { it == "no one else is around right now." }
        }
    }

    /** Sending from the composer posts the message and empties the field. */
    @Test
    fun sendingFromTheComposerClearsIt() {
        rule.awaitChat()
        composerHolding("").performTextInput("synthetic composer message")

        composerHolding("synthetic composer message").performImeAction()

        rule.waitUntil(timeoutMillis = 5_000) {
            timeline().any { it == "synthetic composer message" }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasSetTextAction() and hasText("synthetic composer message"))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    /** Picking a suggested command completes it in the composer. */
    @Test
    fun pickingACommandSuggestionCompletesIt() {
        rule.awaitChat()
        composerHolding("").performTextInput("/cl")

        rule.onNodeWithText("clear chat messages").performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasSetTextAction() and hasText("/clear "))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The composer is the text field that starts empty; the nickname is editable too. */
    private fun composerHolding(text: String): SemanticsNodeInteraction =
        rule.onNode(hasSetTextAction() and hasText(text))

    /** The chat user sheet sends through its own ViewModel and closes. */
    @Test
    fun hugFromTheChatUserSheetSendsTheActionAndCloses() {
        rule.awaitChat()
        rule.navigateTo(ChatUserRoute(nickname = "synthetic", messageId = null))
        rule.awaitSheet()

        rule.onNodeWithText(rule.activity.getString(R.string.action_hug_title, "synthetic"))
            .performClick()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitUntil(timeoutMillis = 5_000) {
            timeline().any { it.contains("hug") && it.contains("synthetic") }
        }
    }

    private fun session() = ChatSessionTestAccess.of(rule.activity)

    private fun timeline(): List<String> {
        var contents: List<String> = emptyList()
        rule.runOnUiThread { contents = session().chatState().messages.value.map { it.content } }
        return contents
    }
}
