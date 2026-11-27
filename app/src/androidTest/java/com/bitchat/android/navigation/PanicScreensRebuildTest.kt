package com.bitchat.android.navigation

import android.os.Process
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.AppPhase
import com.bitchat.android.MainActivity
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.ui.ChatSessionHolder
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A new chat session gives the screens new ViewModels and closes what was open, inside the same
 * Activity and process. The session is replaced directly, so nothing here wipes anything.
 */
@RunWith(AndroidJUnit4::class)
class PanicScreensRebuildTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val PEER = "0123456789abcdef"

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun aNewGenerationGivesANewChatViewModelInTheSameActivity() {
        rule.awaitChat()
        val activity = rule.activity
        val process = Process.myPid()
        val leftResumed = recordLeavingResumed()
        val oldViewModel = awaitChatViewModel()
        val oldState = chatState()

        replaceTheSession()
        val newViewModel = awaitChatViewModelOtherThan(oldViewModel)
        rule.awaitChat()

        assertFalse("the old ViewModel is cleared", oldViewModel.viewModelScope.isActive)
        assertSame(activity, rule.activity)
        assertEquals(Lifecycle.State.RESUMED, rule.activityRule.scenario.state)
        assertEquals(emptyList<Lifecycle.Event>(), leftResumed)
        assertEquals(process, Process.myPid())
        assertTrue(newViewModel.viewModelScope.isActive)

        // The screen now on show sends through the new session.
        val text = "synthetic message after the rebuild"
        rule.onNode(hasSetTextAction() and hasText("")).performTextInput(text)
        rule.onNode(hasSetTextAction() and hasText(text)).performImeAction()
        rule.waitUntil(timeoutMillis = 5_000) { contents(chatState()).contains(text) }
        assertFalse(contents(oldState).contains(text))
        // And the new ViewModel reports back to the composer, which empties.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun aPrivateChatOrSheetCloses_andTheMainChatShows() {
        rule.awaitChat()
        val activity = rule.activity
        val process = Process.myPid()
        openThePrivateChatWithItsSheet()

        replaceTheSession()

        rule.waitUntil(timeoutMillis = 5_000) { rule.backStack() == listOf(ChatRoute) }
        rule.awaitChat()
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodes(isSheet).fetchSemanticsNodes().isEmpty() }
        var selected: String? = PEER
        rule.runOnUiThread { selected = session().chatState().selectedPrivateChatPeer.value }
        assertNull(selected)
        assertSame(activity, rule.activity)
        assertEquals(Lifecycle.State.RESUMED, rule.activityRule.scenario.state)
        assertEquals(process, Process.myPid())
    }

    @Test
    fun aRecreationWithoutANewGenerationKeepsTheViewModel() {
        rule.awaitChat()
        val activity = rule.activity
        val first = awaitChatViewModel()
        val draft = "synthetic draft across recreation"
        rule.onNode(hasSetTextAction() and hasText("")).performTextInput(draft)

        rule.activityRule.scenario.recreate()
        rule.awaitChat()

        assertNotSame(activity, rule.activity)
        assertSame(first, awaitChatViewModel())
        assertTrue(first.viewModelScope.isActive)
        rule.onNode(hasSetTextAction() and hasText(draft)).assertExists()

        // And the same after a new generation: it is the new one that is kept.
        replaceTheSession()
        val second = awaitChatViewModelOtherThan(first)
        rule.awaitChat()
        // A new session starts without the old one's text.
        rule.onNode(hasSetTextAction() and hasText("")).assertExists()

        rule.activityRule.scenario.recreate()
        rule.awaitChat()

        assertSame(second, awaitChatViewModel())
        assertTrue(second.viewModelScope.isActive)
    }

    @Test
    fun theBackStackHoldsOnlyTheChatRoute() {
        rule.awaitChat()
        rule.navigateTo(MeshPeerListRoute)
        rule.runOnUiThread { rule.activity.navigator.openPrivateChat(PEER, over = MeshPeerListRoute) }
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER) }

        replaceTheSession()

        rule.waitUntil(timeoutMillis = 5_000) { rule.backStack() == listOf(ChatRoute) }
        rule.waitForIdle()
        assertEquals(listOf<Any>(ChatRoute), rule.backStack())
    }

    /**
     * Onboarding covering the chat stays up, and what it set aside belonged to the old session, so
     * finishing it lands on the main chat, not back in the old private chat.
     */
    @Test
    fun aNewGenerationUnderOnboardingLeavesItUpAndForgetsTheStackUnderIt() {
        rule.awaitChat()
        rule.runOnUiThread { rule.activity.navigator.openPrivateChat(PEER) }
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER) }

        try {
            rule.activity.getSharedPreferences("bitchat_permissions", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("first_time_onboarding_complete", false).commit()
            rule.runOnUiThread { rule.activity.startupGate.forcePhase(AppPhase.ONBOARDING) }
            rule.waitUntil(timeoutMillis = 5_000) { rule.backStack() == listOf(OnboardingRoute) }

            replaceTheSession()
            rule.waitForIdle()
            assertEquals(listOf<Any>(OnboardingRoute), rule.backStack())

            rule.runOnUiThread { rule.activity.startupGate.forcePhase(AppPhase.READY) }
            rule.waitUntil(timeoutMillis = 5_000) { rule.backStack() == listOf(ChatRoute) }
        } finally {
            rule.activity.deviceReadiness.markOnboardingComplete()
        }
    }

    private fun session() = ChatSessionTestAccess.of(rule.activity)

    private fun holder(): ChatSessionHolder {
        lateinit var holder: ChatSessionHolder
        rule.runOnUiThread { holder = session().chatSessionHolder() }
        return holder
    }

    private fun replaceTheSession() {
        val holder = holder()
        rule.runOnUiThread { assertTrue(holder.replace()) }
    }

    private fun chatState(): ChatState {
        lateinit var state: ChatState
        rule.runOnUiThread { state = session().chatState() }
        return state
    }

    private fun contents(state: ChatState): List<String> {
        var contents: List<String> = emptyList()
        rule.runOnUiThread { contents = state.messages.value.map { it.content } }
        return contents
    }

    private fun openThePrivateChatWithItsSheet() {
        rule.runOnUiThread { rule.activity.navigator.openPrivateChat(PEER) }
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER) }
        rule.waitForIdle()
        rule.navigateTo(SecurityVerificationRoute(PEER))
        rule.awaitSheet()
    }

    /** Everything that takes the Activity below RESUMED from here on, which should be nothing. */
    private fun recordLeavingResumed(): List<Lifecycle.Event> {
        val events = java.util.Collections.synchronizedList(ArrayList<Lifecycle.Event>())
        rule.runOnUiThread {
            rule.activity.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                        events += event
                    }
                },
            )
        }
        return events
    }

    private fun awaitChatViewModel(): ChatViewModel {
        var found: ChatViewModel? = null
        rule.waitUntil(timeoutMillis = 5_000) {
            found = chatViewModel()
            found != null
        }
        return found!!
    }

    private fun awaitChatViewModelOtherThan(old: ChatViewModel): ChatViewModel {
        var found: ChatViewModel? = null
        rule.waitUntil(timeoutMillis = 5_000) {
            found = chatViewModel()
            found != null && found !== old
        }
        return found!!
    }

    private fun chatViewModel(): ChatViewModel? {
        var found: ChatViewModel? = null
        rule.runOnUiThread {
            found = entryViewModels(screensStore()).filterIsInstance<ChatViewModel>().singleOrNull()
        }
        return found
    }

    private fun screensStore(): ViewModelStore = rule.activity.screenViewModels.viewModelStore
}
