package com.bitchat.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.feature.chat.R
import com.bitchat.android.ui.theme.BitchatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The stateless peer list on synthetic state; no mesh, identity or Nostr is involved. */
class MeshPeerListScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun meshPeople_showsYourNetwork_andTapSendsPersonClicked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Without the conversations and channel, so the people card is on screen.
        val state = previewMeshPeerListUiState().copy(
            conversations = previewEmptyPeerListUiState().conversations,
            channels = emptyList(),
            hasConversations = false,
            hasEarlierSections = false,
        )
        val actions = mutableListOf<MeshPeerListAction>()

        compose.setContent {
            BitchatTheme {
                MeshPeerListScreen(
                    uiState = state,
                    onAction = { actions += it },
                    onDeleteRequested = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.your_network)).assertIsDisplayed()

        compose.onNodeWithText("alice").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf<MeshPeerListAction>(MeshPeerListAction.PersonClicked("0123456789abcdef")),
            actions
        )
    }
}
