package com.bitchat.android.nostr

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/** Run only on a fresh disposable emulator/device with PerformanceTestRunner. No UI or radios start. */
class NostrRelayLiveAuthInstrumentedProbe {
    @Test
    fun authenticatesChannelDmWithPersistedChannelIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val relayUrl = requireNotNull(InstrumentationRegistry.getArguments().getString("nostrLiveRelay")) {
            "Pass the explicitly authorized relay with -e nostrLiveRelay"
        }
        val context = instrumentation.targetContext
        require(InstrumentationRegistry.getArguments().getString("disposableStorage") == "true") {
            "This probe requires explicitly designated disposable app storage"
        }
        require(!File(context.applicationInfo.dataDir, "shared_prefs/bitchat_identity.xml").exists()) {
            "Fresh app storage is required; refusing to read an existing identity store"
        }
        val csvHosts = context.assets.open("nostr_relays.csv").bufferedReader().useLines { lines ->
            lines.drop(1).map { it.substringBefore(',').trim().removePrefix("wss://").trimEnd('/') }.toSet()
        }
        val summary = NostrLiveRelayAuthContract.verify(context, relayUrl, csvHosts)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "LIVE_NIP42_ANDROID_RESULT $summary\n")
        })
    }
}
