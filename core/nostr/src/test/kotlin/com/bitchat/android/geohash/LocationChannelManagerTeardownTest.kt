package com.bitchat.android.geohash

import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NoHttpClients
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.services.TestMeshStores
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocationChannelManagerTeardownTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val gate = LiveLocationPrivacyGate()
    private val teardown = IdentityTeardown()

    private fun providersChangedReceivers() = shadowOf(application).registeredReceivers.count { wrapper ->
        wrapper.intentFilter.hasAction(LocationManager.PROVIDERS_CHANGED_ACTION)
    }

    @Before
    fun declarePlayServicesVersion() {
        // The availability check reads this manifest entry; the unit-test manifest has none.
        application.applicationInfo.metaData = Bundle().apply {
            putInt("com.google.android.gms.version", 12_451_000)
        }
    }

    @Test
    fun `location manager teardown removes both gate listeners and unregisters the receiver`() {
        val before = providersChangedReceivers()
        LocationChannelManager(
            application,
            gate,
            NoHttpClients,
            dispatcher,
            NostrIdentityBridge(TestMeshStores(application).favorites),
            teardown,
        )
        LocationNotesManager(gate, RelayDirectory(NoHttpClients, dispatcher), dispatcher, teardown)
        assertEquals(2, gate.revocationListenerCount)
        assertEquals(before + 1, providersChangedReceivers())

        assertEquals(emptyList<String>(), teardown.runAll())

        assertEquals(0, gate.revocationListenerCount)
        assertEquals(before, providersChangedReceivers())
    }
}
