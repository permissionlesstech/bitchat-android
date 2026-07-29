package com.bitchat.android.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderCrowdingTest {

    @Test
    fun `full header is used at four hundred dp and above`() {
        assertEquals(HeaderCrowdingMode.Full, headerCrowdingMode(400.dp))
        assertEquals(HeaderCrowdingMode.Full, headerCrowdingMode(500.dp))
    }

    @Test
    fun `joined channel count yields before the location label`() {
        assertEquals(
            HeaderCrowdingMode.HideJoinedChannelCount,
            headerCrowdingMode(399.dp)
        )
        assertEquals(
            HeaderCrowdingMode.HideJoinedChannelCount,
            headerCrowdingMode(360.dp)
        )
    }

    @Test
    fun `location action becomes icon only on narrow headers`() {
        assertEquals(
            HeaderCrowdingMode.IconOnlyLocationChannel,
            headerCrowdingMode(359.dp)
        )
        assertEquals(
            HeaderCrowdingMode.IconOnlyLocationChannel,
            headerCrowdingMode(320.dp)
        )
    }
}
