package com.bitchat.android.nostr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelaySettingsTest {

    private lateinit var context: Context
    private val builtIn = setOf("wss://nos.lol")

    @Before
    fun setUp() {
        NostrRelaySettings.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        // Isolate prefs so parallel tests cannot collide.
        context.getSharedPreferences("nostr_relay_settings", Context.MODE_PRIVATE).edit().clear().commit()
        NostrRelaySettings.init(context)
    }

    @After
    fun tearDown() {
        NostrRelaySettings.reset()
        NostrRelaySettings.resetForTests()
    }

    @Test
    fun `add normalizes a bare hostname`() {
        val result = NostrRelaySettings.add("relay.example.com", builtIn)
        assertEquals(
            NostrRelaySettings.AddResult.Success("wss://relay.example.com"),
            result
        )
        assertEquals(listOf("wss://relay.example.com"), NostrRelaySettings.customRelays())
    }

    @Test
    fun `malformed urls are rejected`() {
        assertEquals(
            NostrRelaySettings.AddResult.Failure(NostrRelaySettings.AddFailure.Malformed),
            NostrRelaySettings.add("", builtIn)
        )
        assertEquals(
            NostrRelaySettings.AddResult.Failure(NostrRelaySettings.AddFailure.Malformed),
            NostrRelaySettings.add("ftp://relay.example.com", builtIn)
        )
        assertTrue(NostrRelaySettings.customRelays().isEmpty())
    }

    @Test
    fun `duplicates of custom or built-in are rejected`() {
        assertTrue(NostrRelaySettings.add("wss://relay.example.com", builtIn) is NostrRelaySettings.AddResult.Success)
        assertEquals(
            NostrRelaySettings.AddResult.Failure(NostrRelaySettings.AddFailure.AlreadyPresent),
            NostrRelaySettings.add("relay.example.com", builtIn)
        )
        assertEquals(
            NostrRelaySettings.AddResult.Failure(NostrRelaySettings.AddFailure.AlreadyPresent),
            NostrRelaySettings.add("wss://nos.lol", builtIn)
        )
        assertEquals(listOf("wss://relay.example.com"), NostrRelaySettings.customRelays())
    }

    @Test
    fun `list is capped`() {
        repeat(NostrRelaySettings.maxCustomRelays) { index ->
            val result = NostrRelaySettings.add("relay$index.example.com", builtIn)
            assertTrue(result is NostrRelaySettings.AddResult.Success)
        }
        assertEquals(
            NostrRelaySettings.AddResult.Failure(NostrRelaySettings.AddFailure.LimitReached),
            NostrRelaySettings.add("one.too.many.example.com", builtIn)
        )
        assertEquals(NostrRelaySettings.maxCustomRelays, NostrRelaySettings.customRelays().size)
    }

    @Test
    fun `remove matches the typed form`() {
        NostrRelaySettings.add("wss://relay.example.com", builtIn)
        NostrRelaySettings.add("wss://other.example.com", builtIn)
        NostrRelaySettings.remove("Relay.Example.com")
        assertEquals(listOf("wss://other.example.com"), NostrRelaySettings.customRelays())
    }

    @Test
    fun `reset clears the store`() {
        NostrRelaySettings.add("relay.example.com", builtIn)
        NostrRelaySettings.reset()
        assertTrue(NostrRelaySettings.customRelays().isEmpty())
    }
}
