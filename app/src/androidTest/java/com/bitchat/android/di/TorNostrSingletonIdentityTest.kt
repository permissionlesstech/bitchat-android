package com.bitchat.android.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.testhook.TorNostrTestAccess
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app graph builds each Nostr singleton once, and every consumer holds the same one. Two
 * live-location gates would let turning location off revoke access in one consumer and not the
 * other; two sets of HTTP clients would let relay traffic leave outside the app's Tor policy.
 *
 * Reads the real application graph through a debug entry point; nothing here connects to a relay
 * or reads a location.
 */
@RunWith(AndroidJUnit4::class)
class TorNostrSingletonIdentityTest {

    /** Runs [read] against the app graph on the main thread, where the notes and channel managers live. */
    private fun <T> onMain(read: (TorNostrTestAccess) -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching { read(TorNostrTestAccess.of(ApplicationProvider.getApplicationContext())) }
        }
        return result!!.getOrThrow()
    }

    @Test
    fun everyConsumerHoldsTheInjectedLiveLocationGate() {
        val gate = onMain { it.liveLocationPrivacyGate() }

        assertSame(gate, onMain { it.liveLocationPrivacyGate() })
        assertSame(gate, onMain { it.nostrRelayManager().liveLocationGate })
        assertSame(gate, onMain { it.locationChannelManager().liveLocationGate })
        assertSame(gate, onMain { it.locationNotesManager().liveLocationGate })
    }

    @Test
    fun theRelayManagerUsesTheAppsHttpClients() {
        assertSame(onMain { it.nostrHttpClients() }, onMain { it.nostrRelayManager().httpClients })
    }

    @Test
    fun theRelayManagerIsBuiltOnce() {
        assertSame(onMain { it.nostrRelayManager() }, onMain { it.nostrRelayManager() })
    }
}
