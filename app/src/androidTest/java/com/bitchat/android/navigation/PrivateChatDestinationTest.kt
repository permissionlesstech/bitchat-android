package com.bitchat.android.navigation

import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.bitchat.android.MainActivity
import com.bitchat.android.AppPhase
import com.bitchat.android.MainViewModel
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.R
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.LiveVoiceScope
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.ui.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The private chat as a screen destination, the peer list it opens from and
 * the security sheet stacked over it.
 *
 * The peers are synthetic mesh ids with no device behind them, so a message
 * sent to one goes nowhere. What is mostly under test is which conversation is
 * selected as the stack changes, since that decides where the composer sends.
 */
@RunWith(AndroidJUnit4::class)
class PrivateChatDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val PEER_A = "0123456789abcdef"
        private const val PEER_B = "fedcba9876543210"

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun aChatOpenedFromThePeerListIsPushedOverIt() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.awaitPeerList()

        rule.runOnUiThread {
            rule.activity.navigator.openPrivateChat(
                ContactDirectory.canonicalConversationId(PEER_A),
                over = MeshPeerListRoute,
            )
        }
        rule.waitForIdle()

        assertEquals(
            listOf(ChatRoute, MeshPeerListRoute, PrivateChatRoute(PEER_A)),
            rule.backStack()
        )
        awaitSelection(PEER_A)
        awaitPrivateChatScreen()

        rule.pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == MeshPeerListRoute }
        rule.awaitPeerList()
        awaitSelection(null)

        rule.pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        assertFalse(rule.activity.isFinishing)
    }

    /**
     * Live voice autoplays, and is read, only in the conversation on screen.
     * Chat is not composed under the peer list, so a chat pushed over the list
     * must say it is on screen itself, and must stop saying so when it leaves.
     */
    @Test
    fun liveVoiceFollowsAChatPushedOverThePeerList() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.awaitPeerList()

        rule.runOnUiThread {
            rule.activity.navigator.openPrivateChat(
                ContactDirectory.canonicalConversationId(PEER_A),
                over = MeshPeerListRoute,
            )
        }
        awaitPrivateChatScreen()

        rule.waitUntil(timeoutMillis = 5_000) {
            liveVoice().isVisible(LiveVoiceScope.DIRECT_MESSAGE, PEER_A)
        }
        assertFalse(liveVoice().isVisible(LiveVoiceScope.PUBLIC_MESH, PEER_B))

        rule.pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == MeshPeerListRoute }
        rule.waitUntil(timeoutMillis = 5_000) {
            !liveVoice().isVisible(LiveVoiceScope.DIRECT_MESSAGE, PEER_A)
        }
    }

    /**
     * The pushed stack survives the Activity being recreated, as on a
     * configuration change. The stack is saved and restored through the
     * encoder on the way, but the navigator is Activity-retained and keeps it
     * in memory too, so a restore that came back empty would go unnoticed.
     * This is recreation, not process death.
     */
    @Test
    fun theListAndChatSurviveActivityRecreation() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.runOnUiThread {
            rule.activity.navigator.openPrivateChat(PEER_A, over = MeshPeerListRoute)
        }
        awaitSelection(PEER_A)

        rule.activityRule.scenario.recreate()

        rule.waitUntil(5_000) {
            rule.backStack() == listOf(ChatRoute, MeshPeerListRoute, PrivateChatRoute(PEER_A))
        }
        awaitSelection(PEER_A)
    }

    /**
     * Turning Bluetooth off mid-conversation sends the app back to onboarding.
     * Finishing it returns to the list and chat the user was in, not to the
     * timeline, and Back from onboarding never reaches them meanwhile.
     */
    @Test
    fun theListAndChatSurviveATripThroughOnboarding() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.runOnUiThread {
            rule.activity.navigator.openPrivateChat(PEER_A, over = MeshPeerListRoute)
        }
        awaitSelection(PEER_A)

        showOnboardingStep(OnboardingState.BLUETOOTH_CHECK)
        rule.waitUntil(5_000) { rule.backStack() == listOf(OnboardingRoute) }

        forcePhase(AppPhase.READY)
        rule.waitUntil(5_000) {
            rule.backStack() == listOf(ChatRoute, MeshPeerListRoute, PrivateChatRoute(PEER_A))
        }
        awaitSelection(PEER_A)
    }

    /** The gate sends the app to onboarding, on [step]. */
    private fun showOnboardingStep(step: OnboardingState) {
        rule.runOnUiThread {
            ViewModelProvider(rule.activity)[MainViewModel::class.java].updateOnboardingState(step)
            rule.activity.startupGate.forcePhase(AppPhase.ONBOARDING)
        }
        rule.waitForIdle()
    }

    private fun forcePhase(phase: AppPhase) {
        rule.runOnUiThread { rule.activity.startupGate.forcePhase(phase) }
        rule.waitForIdle()
    }

    /** A message sent from the private chat's composer lands in its conversation and clears it. */
    @Test
    fun sendingFromThePrivateComposerClearsIt() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        awaitSelection(PEER_A)

        // Chat leaves composition under a screen; the DM composer is still the last.
        rule.onAllNodes(hasSetTextAction() and hasText("")).onLast()
            .performTextInput("synthetic private message")
        rule.onNode(hasSetTextAction() and hasText("synthetic private message"))
            .performImeAction()

        rule.waitUntil(timeoutMillis = 5_000) {
            var contents: List<String> = emptyList()
            rule.runOnUiThread {
                contents = session().chatState().privateChats.value[PEER_A]
                    .orEmpty().map { it.content }
            }
            "synthetic private message" in contents
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasSetTextAction() and hasText("synthetic private message"))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun backFromAPrivateChatReturnsToChatAndEndsIt() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        awaitSelection(PEER_A)

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        awaitSelection(null)
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun openingOneChatFromAnotherLeavesTheNewOneSelected() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        awaitSelection(PEER_A)

        openPrivateChat(PEER_B)
        awaitSelection(PEER_B)
        // Idle once the replaced screen has slid away and left composition.
        rule.waitForIdle()

        assertEquals(PEER_B, selection())
        assertEquals(listOf(ChatRoute, PrivateChatRoute(PEER_B)), rule.backStack())
    }

    /**
     * The replaced chat's teardown and the new chat's start race: the start
     * waits on loading history, the teardown on the old sheet sliding away.
     * Whichever loses, a late teardown must not end the chat now on screen.
     * The flow above cannot force that order, so this calls it directly.
     */
    @Test
    fun aLateTeardownOfAReplacedChatLeavesTheNewOneSelected() {
        rule.awaitChat()
        openPrivateChat(PEER_B)
        awaitSelection(PEER_B)

        rule.runOnUiThread { session().privateChatSession().end(PEER_A) }

        assertEquals(PEER_B, selection())
    }

    @Test
    fun theChatStaysSelectedAcrossRecreation() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        awaitSelection(PEER_A)

        rule.activityRule.scenario.recreate()
        // Chat's header is not on screen under the private chat, so wait on the stack.
        rule.waitUntil(timeoutMillis = 15_000) { rule.topOfStack() == PrivateChatRoute(PEER_A) }
        rule.waitForIdle()

        assertEquals(PrivateChatRoute(PEER_A), rule.topOfStack())
        awaitSelection(PEER_A)
    }

    @Test
    fun backFromSecurityVerificationReturnsToTheChat() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        awaitSelection(PEER_A)
        rule.navigateTo(SecurityVerificationRoute(PEER_A))
        // The one sheet, over the private chat screen.
        rule.awaitSheet()

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER_A) }
        rule.waitForIdle()
        assertEquals(PEER_A, selection())
        assertFalse(rule.activity.isFinishing)
    }

    /**
     * The security sheet reads its own ViewModel. A mesh peer with no
     * fingerprint yet, as the synthetic one has, is offered a handshake.
     */
    @Test
    fun theSecuritySheetOffersAHandshakeToAPeerWithNoFingerprint() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        rule.navigateTo(SecurityVerificationRoute(PEER_A))

        val startHandshake = rule.activity.getString(R.string.fingerprint_start_handshake)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(startHandshake).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Closing a chat opened from a notification lands on the conversation list. */
    @Test
    fun aNotificationOpensTheChatOverThePeerList() {
        rule.awaitChat()
        val intent = Intent(rule.activity, MainActivity::class.java)
            .putExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, true)
            .putExtra(NotificationManager.EXTRA_PEER_ID, PEER_A)

        // What the platform does for a singleTop Activity already on top. Starting
        // the Activity for real pauses the one under test and races its teardown.
        // onNewIntent replaces the Activity's intent, and ActivityScenario finds
        // its Activity by the intent it launched it with, so that is put back.
        rule.runOnUiThread {
            val launchIntent = rule.activity.intent
            InstrumentationRegistry.getInstrumentation()
                .callActivityOnNewIntent(rule.activity, intent)
            rule.activity.intent = launchIntent
        }
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER_A) }

        assertEquals(
            listOf(ChatRoute, MeshPeerListRoute, PrivateChatRoute(PEER_A)),
            rule.backStack()
        )
        // The stack changes before anything is drawn from it. A press before
        // the screens exist reaches the Activity, with nothing yet to pop.
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() is PrivateChatRoute }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(isSheet).fetchSemanticsNodes().isEmpty())

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == MeshPeerListRoute }
        awaitSelection(null)
    }

    @Test
    fun theBackArrowLeavesThePrivateChat() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        awaitSelection(PEER_A)

        rule.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.back)
        ).performClick()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        awaitSelection(null)
    }

    /**
     * The composer sits just above the keyboard once it is open: not under it,
     * and not lifted by the keyboard's height twice. The gap allowed is the
     * input section's own padding below the field, about 25dp, with room.
     */
    @Test
    fun imeKeepsComposerVisible() {
        rule.awaitChat()
        openPrivateChat(PEER_A)
        awaitPrivateChatScreen()
        val composer = rule.onAllNodes(hasSetTextAction()).onLast()
        composer.performClick()
        val decorView = rule.activity.window.decorView
        fun imeBottom() = androidx.core.view.ViewCompat.getRootWindowInsets(decorView)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        fun composerBottom() = composer.fetchSemanticsNode().boundsInWindow.bottom
        rule.waitUntil(5_000) { imeBottom() > 0 }
        // The composer rides the keyboard up as it animates in, and a doubled
        // padding would pass through the allowed gap on the way. Measure once
        // it has held still for a while.
        var lastBottom = Float.NaN
        var stillSince = 0L
        rule.waitUntil(5_000) {
            val now = android.os.SystemClock.uptimeMillis()
            val bottom = composerBottom()
            if (bottom != lastBottom) {
                lastBottom = bottom
                stillSince = now
            }
            now - stillSince >= 500
        }

        val imeTop = decorView.height - imeBottom()
        val composerBottom = composerBottom()
        val gap = imeTop - composerBottom
        val maxGap = 48 * rule.activity.resources.displayMetrics.density

        assertTrue("composer bottom $composerBottom is under the keyboard top $imeTop", gap >= 0f)
        assertTrue("composer bottom $composerBottom is $gap px above the keyboard top $imeTop", gap <= maxGap)
    }

    private fun awaitPrivateChatScreen() {
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() is PrivateChatRoute }
        rule.waitForIdle()
        // A screen, not a sheet: nothing on screen announces a pane title.
        assertEquals(0, rule.onAllNodes(isSheet).fetchSemanticsNodes().size)
    }

    private fun session() = ChatSessionTestAccess.of(rule.activity)

    private fun liveVoice() = LiveVoiceManager.getInstance(rule.activity)

    private fun openPrivateChat(peerID: String) {
        rule.runOnUiThread {
            rule.activity.navigator.openPrivateChat(ContactDirectory.canonicalConversationId(peerID))
        }
        rule.waitForIdle()
    }

    private fun selection(): String? {
        var selected: String? = null
        rule.runOnUiThread { selected = session().chatState().selectedPrivateChatPeer.value }
        return selected
    }

    private fun awaitSelection(peerID: String?) {
        rule.waitUntil(timeoutMillis = 5_000) { selection() == peerID }
    }
}
