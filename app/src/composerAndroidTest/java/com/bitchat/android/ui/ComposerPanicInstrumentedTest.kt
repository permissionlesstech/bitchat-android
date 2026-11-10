package com.bitchat.android.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModelProvider
import com.bitchat.android.services.AppStateStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.BitchatApplication
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposerPanicInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val editor = hasSetTextAction() and
        SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Send)

    @Test
    fun confirmedPanicClearsDraftWhileCancelAndFreshTypingPreserveText() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(editor).fetchSemanticsNodes().size == 1
        }
        assertTrue(context.applicationContext is BitchatApplication)
        compose.onNode(editor).assertTextEquals("")
        // Retain a real earlier completion so the confirmation cannot pass on a stale banner.
        val previousIds = AppStateStore.publicMessages.value.map { it.id }.toSet()
        val viewModel = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
        compose.runOnIdle { viewModel.panicClearAllData() }
        awaitNewCompletion(previousIds)
        compose.onNode(editor).performTextInput("SYNTHETIC_UNSENT_DRAFT")

        openPanicConfirmation()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.onNode(editor).assertTextEquals("SYNTHETIC_UNSENT_DRAFT")

        openPanicConfirmation()
        val beforeConfirmation = AppStateStore.publicMessages.value.map { it.id }.toSet()
        compose.onNodeWithText(context.getString(R.string.panic_confirm_action)).performClick()
        awaitNewCompletion(beforeConfirmation)
        compose.onNode(editor).assertTextEquals("")
        compose.onNode(editor).performTextInput("SYNTHETIC_FRESH_DRAFT")
        compose.onNode(editor).assertTextEquals("SYNTHETIC_FRESH_DRAFT")
    }

    private fun awaitNewCompletion(previousIds: Set<String>) {
        compose.waitUntil(30_000) {
            AppStateStore.publicMessages.value.any {
                it.id !in previousIds && it.content == context.getString(R.string.system_panic_completed)
            }
        }
    }

    private fun openPanicConfirmation() {
        compose.mainClock.autoAdvance = false
        try {
            repeat(3) {
                compose.onNodeWithContentDescription(context.getString(R.string.cd_open_about))
                    .performClick()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithText(context.getString(R.string.panic_confirm_title)).assertIsDisplayed()
    }
}
