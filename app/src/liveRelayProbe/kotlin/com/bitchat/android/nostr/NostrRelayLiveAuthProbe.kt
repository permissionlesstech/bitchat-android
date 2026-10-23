package com.bitchat.android.nostr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Explicit live probe using isolated Robolectric storage. */
@RunWith(RobolectricTestRunner::class)
class NostrRelayLiveAuthProbe {
    @Test
    fun `CSV relay requires NIP-42 and serves the real relay manager after authentication`() {
        val relayUrl = requireNotNull(System.getProperty("bitchat.liveRelay"))
        val csvHosts = File("src/main/assets/nostr_relays.csv").useLines { lines ->
            lines.drop(1).map { it.substringBefore(',').trim().removePrefix("wss://").trimEnd('/') }.toSet()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val summary = NostrLiveRelayAuthContract.verify(context, relayUrl, csvHosts)
        println("LIVE_NIP42_RESULT $summary")
    }
}
