package com.bitchat.android.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModelProvider
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.services.AppStateStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposerEdgeCasesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val editor = hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Send)
    private fun field() = compose.onNode(editor)
    private fun start() {
        compose.waitUntil(30_000) { compose.onAllNodes(editor).fetchSemanticsNodes().size == 1 }
        field().assertTextEquals("")
    }
    private fun openConfirmation() {
        compose.mainClock.autoAdvance = false
        try { repeat(3) { compose.onNodeWithContentDescription(context.getString(R.string.cd_open_about)).performClick() } }
        finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithText(context.getString(R.string.panic_confirm_title)).assertIsDisplayed()
    }
    private fun confirmAndWait() {
        val prior = AppStateStore.publicMessages.value.map { it.id }.toSet()
        compose.onNodeWithText(context.getString(R.string.panic_confirm_action)).performClick()
        compose.waitUntil(30_000) {
            AppStateStore.publicMessages.value.any {
                it.id !in prior && it.content == context.getString(R.string.system_panic_completed)
            }
        }
    }
    @Test fun backPreservesMultilineUnicodeAndSelectedTextThenWipeClears() {
        start()
        val draft = "SYNTHETIC\n雪🙂 é\nline two"
        field().performTextInput(draft)
        field().performTextInputSelection(TextRange(2, 7))
        openConfirmation()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        field().assertTextEquals(draft)
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 7)))
        openConfirmation(); confirmAndWait()
        field().assertTextEquals("")
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange.Zero))
        field().performTextInput("SYNTHETIC_FRESH")
        field().assertTextEquals("SYNTHETIC_FRESH")
    }
    @Test fun emptyAndRepeatedWipesPreserveFreshTyping() {
        start(); openConfirmation(); confirmAndWait(); field().assertTextEquals("")
        field().performTextInput("SYNTHETIC_SECOND")
        openConfirmation(); confirmAndWait(); field().assertTextEquals("")
        field().performTextInput("SYNTHETIC_AFTER_SECOND")
        field().assertTextEquals("SYNTHETIC_AFTER_SECOND")
    }
    @Test fun confirmedWipeDismissesCommandSuggestions() {
        start(); field().performTextInput("/w")
        val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
        compose.waitUntil(5_000) { vm.showCommandSuggestions.value && vm.commandSuggestions.value.isNotEmpty() }
        val command = vm.commandSuggestions.value.first().command
        compose.onNode(hasText(command) and !hasSetTextAction(), useUnmergedTree = true).assertIsDisplayed()
        openConfirmation(); confirmAndWait()
        field().assertTextEquals("")
        assertFalse("pre-wipe command suggestion popup must be dismissed", vm.showCommandSuggestions.value)
        compose.onNode(hasText(command) and !hasSetTextAction(), useUnmergedTree = true).assertDoesNotExist()
        field().performTextInput("/w")
        compose.waitUntil(5_000) { vm.showCommandSuggestions.value }
        compose.onNode(hasText(command) and !hasSetTextAction(), useUnmergedTree = true).assertIsDisplayed()
    }
    private fun seedMention(nickname: String = "SyntheticMentionPeer"): ChatViewModel {
        val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
        compose.runOnIdle {
            vm.meshService.updatePeerInfo(
                peerID = "aaaabbbbccccdddd", nickname = nickname,
                noisePublicKey = ByteArray(32) { 1 }, signingPublicKey = ByteArray(32) { 2 },
                isVerified = false
            )
            assertEquals(nickname, vm.meshServiceFacade.getPeerNicknames()["aaaabbbbccccdddd"])
        }
        field().performTextInput("@" + nickname.dropLast(4))
        compose.waitUntil(5_000) { vm.showMentionSuggestions.value }
        compose.onNodeWithText(nickname, substring = true).assertIsDisplayed()
        return vm
    }
    @Test fun confirmedWipeDismissesMentionSuggestions() {
        start(); val vm = seedMention()
        openConfirmation(); confirmAndWait(); field().assertTextEquals("")
        assertFalse("synthetic peer must be removed from fresh mesh", vm.meshServiceFacade.getPeerNicknames().containsKey("aaaabbbbccccdddd"))
        compose.onNodeWithText("SyntheticMentionPeer", substring = true).assertDoesNotExist()
        assertFalse("pre-wipe mention suggestion popup must be dismissed", vm.showMentionSuggestions.value)
        seedMention("FreshSyntheticPeer")
        compose.onNodeWithText("FreshSyntheticPeer", substring = true).performClick()
        field().assertTextEquals("@FreshSyntheticPeer ")
    }
    @Test fun exitingMentionRowsCannotRefillInput() {
        start()
        // Exercise a captured callback before any redraw, separately from pointer timing.
        val vm = seedMention()
        val staleAction = compose.onNodeWithText("SyntheticMentionPeer", substring = true)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        openConfirmation()
        val confirmAction = compose.onNodeWithText(context.getString(R.string.panic_confirm_action))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread {
                assertTrue(confirmAction())
                assertFalse(vm.showMentionSuggestions.value)
                assertTrue(staleAction())
            }
            compose.mainClock.advanceTimeByFrame()
            field().assertTextEquals("")
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(30_000) {
            !ChatViewModel::class.java.getDeclaredField("panicClearInProgress")
                .apply { isAccessible = true }.getBoolean(vm)
        }
        for (frames in listOf(1, 2)) {
            field().performTextReplacement("")
            seedMention()
            openConfirmation()
            compose.mainClock.autoAdvance = false
            try {
                confirmAndWait()
                repeat(frames) { compose.mainClock.advanceTimeByFrame() }
                val retained = compose.onAllNodesWithText("SyntheticMentionPeer", substring = true)
                val count = retained.fetchSemanticsNodes().size
                println("EXIT_FRAME=$frames retainedRows=$count")
                assertEquals("fixture must click a retained mention exit row at frame $frames", 1, count)
                retained[0].performClick()
                compose.mainClock.advanceTimeBy(500)
                field().assertTextEquals("")
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }
}
