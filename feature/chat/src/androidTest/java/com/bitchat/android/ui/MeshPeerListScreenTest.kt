package com.bitchat.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

    @Test
    fun swipeRight_togglesReadOnce_andRowSlidesBack() {
        val actions = mutableListOf<MeshPeerListAction>()
        compose.setContent {
            BitchatTheme {
                MeshPeerListScreen(
                    uiState = previewMeshPeerListUiState(),
                    onAction = { actions += it },
                    onDeleteRequested = {},
                )
            }
        }
        val restingLeft = compose.onNodeWithText("bob").getBoundsInRoot().left

        swipeAcrossRow("bob", leftToRight = true)
        compose.waitForIdle()

        assertEquals(
            listOf<MeshPeerListAction>(MeshPeerListAction.SwipeToggleRead("1111111111111111", true)),
            actions
        )
        assertEquals(restingLeft, compose.onNodeWithText("bob").getBoundsInRoot().left)
    }

    @Test
    fun swipeLeft_asksToDeleteOnce_andRowSlidesBack() {
        val requested = mutableListOf<String>()
        compose.setContent {
            BitchatTheme {
                MeshPeerListScreen(
                    uiState = previewMeshPeerListUiState(),
                    onAction = {},
                    onDeleteRequested = { requested += it.conversationID },
                )
            }
        }
        val restingLeft = compose.onNodeWithText("carol").getBoundsInRoot().left

        swipeAcrossRow("carol", leftToRight = false)
        compose.waitForIdle()

        assertEquals(listOf("2222222222222222"), requested)
        assertEquals(restingLeft, compose.onNodeWithText("carol").getBoundsInRoot().left)
    }

    /** A full-width swipe through the row that shows [text], as a thumb makes it. */
    private fun swipeAcrossRow(text: String, leftToRight: Boolean) {
        val y = compose.onNodeWithText(text).getBoundsInRoot().let { (it.top + it.bottom) / 2 }
        compose.onRoot().performTouchInput {
            val yPx = y.toPx()
            val start = Offset(width * 0.1f, yPx)
            val end = Offset(width * 0.9f, yPx)
            if (leftToRight) swipe(start, end, durationMillis = 300)
            else swipe(end, start, durationMillis = 300)
        }
    }
}
