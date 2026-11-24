package com.bitchat.android.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderCrowdingModeTest {

    @Test
    fun accountsForIncreasedFontScale() {
        assertEquals(HeaderCrowdingMode.Full, headerCrowdingMode(412.dp, fontScale = 1.0f))
        assertEquals(HeaderCrowdingMode.HideJoinedChannelCount, headerCrowdingMode(412.dp, fontScale = 1.1f))
        assertEquals(HeaderCrowdingMode.IconOnlyLocationChannel, headerCrowdingMode(412.dp, fontScale = 1.25f))
    }
}
