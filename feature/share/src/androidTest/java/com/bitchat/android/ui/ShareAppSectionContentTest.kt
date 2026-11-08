package com.bitchat.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.feature.share.R
import com.bitchat.android.ui.theme.BitchatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShareAppSectionContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun readyState_showsShareRows_andTapSendsAction() {
        val actions = mutableListOf<ApkAction>()
        val hotspotTitle = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.hotspot_share_via)

        compose.setContent {
            BitchatTheme {
                ShareAppSectionContent(
                    uiState = previewApkUiState(),
                    onAction = { actions += it }
                )
            }
        }

        compose.onNodeWithText(hotspotTitle).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf<ApkAction>(ApkAction.HotspotShareClicked), actions)
    }
}
