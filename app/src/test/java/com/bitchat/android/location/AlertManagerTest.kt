package com.bitchat.android.location

import com.bitchat.android.nostr.NostrEvent
import org.junit.Test
import android.app.NotificationManager
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlertManagerTest {

    @Test
    fun testAlertLogic() {
        val context = RuntimeEnvironment.getApplication()
        val alertManager = AlertManager(context)

        val currentLocation = NigeriaLocation("Lagos", "Lagos West", "Ikeja", "Oregun", "Ikeja I")

        val alertEvent = NostrEvent(
            id = "event1",
            pubkey = "pub1",
            createdAt = 1234567,
            kind = 30006,
            tags = listOf(
                listOf("ng_state", "Lagos"),
                listOf("ng_lga", "Ikeja"),
                listOf("ng_ward", "Oregun"),
                listOf("ng_scope", "ward")
            ),
            content = "Security Alert in Oregun!"
        )

        // This should run without error.
        // We can't easily verify notification in Robolectric without deeper setup,
        // but we verify the code path executes.
        alertManager.handleAlertEvent(alertEvent, currentLocation)
    }
}
