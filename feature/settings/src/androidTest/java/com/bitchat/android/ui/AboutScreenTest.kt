package com.bitchat.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.feature.settings.R
import com.bitchat.android.ui.theme.BitchatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsTab_torSwitchSendsAction_andCardShowsForTorOn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = previewAboutUiState()
        val torTitle = context.getString(R.string.about_tor_title)
        val connected = context.getString(R.string.about_tor_connected, state.torStatus.bootstrapPercent)
        val actions = mutableListOf<AboutAction>()

        compose.setContent {
            BitchatTheme {
                AboutScreen(
                    uiState = state,
                    onAction = { actions += it },
                    shareSection = {},
                    initialTab = AboutTab.Settings,
                )
            }
        }

        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText(connected))
        compose.onNodeWithText(connected).assertIsDisplayed()

        list.performScrollToNode(hasText(torTitle))
        compose.onNodeWithText(torTitle).performClick()
        compose.waitForIdle()
        assertEquals(listOf<AboutAction>(AboutAction.TorToggled(enabled = false)), actions)
    }
}
