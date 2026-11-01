package com.bitchat.android.ui

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restoring the composer's saved text, the way process death restores it.
 *
 * StateRestorationTester saves state and recomposes from it, which Activity
 * recreation cannot stand in for: recreation keeps the ViewModel, so the
 * selected conversation never changes across it.
 */
@RunWith(AndroidJUnit4::class)
class ComposerTextRestoreTest {

    @get:Rule
    val rule = createComposeRule()

    private val noStoredDraft: (String?) -> String = { "" }

    @Test
    fun textComesBackIntoTheConversationItWasTypedIn() {
        val restoration = StateRestorationTester(rule)
        lateinit var text: MutableState<TextFieldValue>
        restoration.setContent { text = rememberComposerText("peer-a", noStoredDraft) }
        rule.runOnIdle { text.value = TextFieldValue("unsent") }

        restoration.emulateSavedInstanceStateRestore()

        rule.runOnIdle { assertEquals("unsent", text.value.text) }
    }

    @Test
    fun privateTextDoesNotComeBackIntoThePublicComposer() {
        val restoration = StateRestorationTester(rule)
        lateinit var text: MutableState<TextFieldValue>
        // A plain var, not state: the peer has to change between saving and
        // restoring, not trigger a recomposition before the save.
        var conversation: String? = "peer-a"
        restoration.setContent { text = rememberComposerText(conversation, noStoredDraft) }
        rule.runOnIdle { text.value = TextFieldValue("private words") }

        // Process death: the selected peer is not saved and comes back null.
        conversation = null
        restoration.emulateSavedInstanceStateRestore()

        rule.runOnIdle { assertEquals("", text.value.text) }
    }
}
