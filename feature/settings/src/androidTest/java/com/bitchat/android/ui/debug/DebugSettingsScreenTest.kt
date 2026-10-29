package com.bitchat.android.ui.debug

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.feature.settings.R
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.ui.theme.BitchatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DebugSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun togglesSendActions_andNoMesh_showsNoMeshText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val address = "00:11:22:33:44:55"
        val state = previewDebugSettingsUiState().copy(
            meshAvailable = false,
            scanResults = listOf(DebugScanResult(null, address, -60, null)),
        )
        val actions = mutableListOf<DebugSettingsAction>()

        compose.setContent {
            BitchatTheme {
                DebugSettingsScreen(
                    uiState = state,
                    onAction = { actions += it },
                    spamFilterSection = { SpamFilterDebugSection(SpamFilterPreferences, SpamFilter.shared) },
                )
            }
        }

        // Find the verbose logging switch by its label; the spam filter section above it has switches too.
        val verbose = context.getString(R.string.debug_verbose_logging)
        val list = compose.onNode(hasScrollAction())
        val verboseSwitch = isToggleable() and hasAnySibling(hasText(verbose))
        list.performScrollToNode(verboseSwitch)
        // Scrolling to an item parks it at the top of the list, under the overlaid top bar on tall screens.
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -TOP_BAR_CLEARANCE_PX) }
        compose.onNode(verboseSwitch).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf<DebugSettingsAction>(DebugSettingsAction.VerboseLoggingToggled(true)), actions)

        list.performScrollToNode(hasText("No mesh running"))
        compose.onAllNodesWithText("No mesh running").onFirst().assertIsDisplayed()

        val connect = context.getString(R.string.debug_connect)
        list.performScrollToNode(hasText(connect))
        compose.onNodeWithText(connect).performClick()
        compose.waitForIdle()
        assertEquals(DebugSettingsAction.ConnectClicked(address), actions.last())
    }
}

private const val TOP_BAR_CLEARANCE_PX = 400f
