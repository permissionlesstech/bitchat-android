package com.bitchat.android.hotspot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bitchat.android.ui.theme.BitchatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HotspotScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun activeState_showsCredentials_closeSendsAction() {
        val actions = mutableListOf<HotspotAction>()

        compose.setContent {
            BitchatTheme {
                HotspotScreen(
                    uiState = HotspotUiState.Active(
                        ssid = "bitchat-test",
                        password = "synthetic-pass",
                        ipAddress = "192.0.2.1",
                        port = 9999,
                        connectedPeers = 1
                    ),
                    onAction = { actions += it }
                )
            }
        }

        compose.onNodeWithText("Hotspot Active").assertIsDisplayed()
        compose.onNodeWithText("bitchat-test").assertIsDisplayed()
        compose.onNodeWithText("synthetic-pass").assertIsDisplayed()

        compose.onNodeWithText("Website").performClick()
        compose.onNodeWithText("http://192.0.2.1:9999").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(listOf<HotspotAction>(HotspotAction.CloseClicked), actions)
    }
}
