package com.bitchat.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.feature.location.R
import com.bitchat.android.ui.theme.BitchatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The stateless notes screen on synthetic state; no location service is involved. */
class LocationNotesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun notesMode_showsNotes_sendSendsAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = previewLocationNotesUiState().copy(draft = "synthetic draft", sendEnabled = true)
        val actions = mutableListOf<LocationNotesAction>()

        compose.setContent {
            BitchatTheme {
                LocationNotesScreen(uiState = state, onAction = { actions += it })
            }
        }

        compose.onNodeWithText("Synthetic Building").assertIsDisplayed()
        compose.onNodeWithText("synthetic note").assertIsDisplayed()
        compose.onNodeWithText("@tester").assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.send_message)).performClick()
        compose.waitForIdle()
        assertEquals(listOf<LocationNotesAction>(LocationNotesAction.SendClicked), actions)
    }
}
