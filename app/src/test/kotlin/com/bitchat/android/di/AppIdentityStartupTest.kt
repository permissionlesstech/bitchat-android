package com.bitchat.android.di

import android.app.Application
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityComponentBuilder
import com.bitchat.android.identity.scope.IdentityEnder
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.panic.testPanicMarker
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.service.MeshTransportToggles
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.wifiaware.WifiAwareController
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AppIdentityStartupTest {

    /** One identity's objects, as the current-identity providers hand them out. */
    private class Identity {
        val notes = mock<LocationNotesInitializer>()
        val favorites = mock<FavoritesPersistenceService>()
        val store = mock<AppStateStore>()
        val nostrIdentity = mock<NostrIdentityBridge>()
        val wifiAware = mock<WifiAwareController>()
        val toggles = mock<MeshTransportToggles>()
        val aliases = mock<GeohashAliasRegistry>()
        val conversations = mock<GeohashConversationRegistry>()
        val runtime = mock<NostrBackgroundRuntime>()
    }

    private val application = mock<Application>()
    private val debugPreferences = mock<DebugPreferenceManager>()
    private val debugSettings = DebugSettingsManager(DebugPreferenceManager())
    private val holder = IdentityHolder(
        Provider {
            object : IdentityComponentBuilder {
                override fun build(): IdentityComponent = object : IdentityComponent {}
            }
        },
        IdentityEnder { emptyList() },
        testPanicMarker(),
    )
    private var current = Identity()

    private val startup = AppIdentityStartup(
        application = application,
        identity = holder,
        debugPreferences = { debugPreferences },
        debugSettings = { debugSettings },
        powPreferences = { error("only the event processor asks for it") },
        locationNotesInitializer = { current.notes },
        favoritesPersistence = { current.favorites },
        appStateStore = { current.store },
        nostrIdentityBridge = { current.nostrIdentity },
        wifiAwareController = { current.wifiAware },
        meshTransportToggles = { current.toggles },
        geohashAliases = { current.aliases },
        geohashConversations = { current.conversations },
        nostrBackgroundRuntime = { current.runtime },
        nostrTransport = { error("only the event processor asks for it") },
        contactDirectory = { mock<ContactDirectory>() },
        peerFingerprints = { mock<PeerFingerprintManager>() },
        spamFilter = { mock<SpamFilter>() },
        seenMessageStore = { error("only the event processor asks for it") },
    )

    @Test
    fun `startup runs the per-identity steps in order`() {
        whenever(debugPreferences.getWifiAwareEnabled(false)).thenReturn(true)
        val identity = current

        startup.onIdentityStarted()

        inOrder(
            identity.notes,
            identity.favorites,
            identity.store,
            identity.nostrIdentity,
            identity.wifiAware,
            identity.aliases,
            identity.conversations,
            identity.runtime,
        ) {
            verify(identity.notes).initialize(application)
            verify(identity.favorites).initialize()
            verify(identity.store).initializeConversationPersistence(application)
            verify(identity.nostrIdentity).getCurrentNostrIdentity(application)
            verify(identity.wifiAware).initialize(application, true)
            verify(identity.aliases).initialize(application)
            verify(identity.conversations).initialize(application)
            verify(identity.runtime).initialize(any(), any())
        }
    }

    @Test
    fun `the transport toggles are registered after Wi-Fi Aware and before the registries`() {
        val identity = current
        var togglesAtWifiAware: Any? = "unread"
        var togglesAtAliases: Any? = "unread"
        whenever(identity.wifiAware.initialize(any(), any())).doAnswer {
            togglesAtWifiAware = debugSettings.transportToggles
            Unit
        }
        whenever(identity.aliases.initialize(any())).doAnswer {
            togglesAtAliases = debugSettings.transportToggles
            Unit
        }

        startup.onIdentityStarted()

        assertEquals(null, togglesAtWifiAware)
        assertSame(identity.toggles, togglesAtAliases)
    }

    @Test
    fun `restoring and starting are the two halves of a run`() {
        val identity = current

        startup.restoreIdentityState()

        verify(identity.nostrIdentity).getCurrentNostrIdentity(application)
        verify(identity.wifiAware, times(0)).initialize(any(), any())
        verify(identity.runtime, times(0)).initialize(any(), any())

        startup.startIdentityServices()

        verify(identity.notes, times(1)).initialize(application)
        verify(identity.wifiAware).initialize(application, false)
        verify(identity.runtime).initialize(any(), any())
    }

    @Test
    fun `a second run initializes the new identity's objects`() {
        val first = current
        startup.onIdentityStarted()

        val second = Identity()
        current = second
        startup.onIdentityStarted()

        listOf(first, second).forEach { identity ->
            verify(identity.notes, times(1)).initialize(application)
            verify(identity.favorites, times(1)).initialize()
            verify(identity.store, times(1)).initializeConversationPersistence(application)
            verify(identity.nostrIdentity, times(1)).getCurrentNostrIdentity(application)
            verify(identity.wifiAware, times(1)).initialize(application, false)
            verify(identity.aliases, times(1)).initialize(application)
            verify(identity.conversations, times(1)).initialize(application)
            verify(identity.runtime, times(1)).initialize(any(), any())
        }
        assertSame(second.toggles, debugSettings.transportToggles)
    }

    @Test
    fun `a failing step does not stop the ones after it`() {
        val identity = current
        whenever(identity.favorites.initialize()).doThrow(IllegalStateException("synthetic"))
        whenever(identity.wifiAware.initialize(any(), any())).doThrow(IllegalStateException("synthetic"))

        startup.onIdentityStarted()

        verify(identity.store).initializeConversationPersistence(application)
        verify(identity.runtime).initialize(any(), any())
    }

    @Test
    fun `an identity counts as started only once its last step has run`() {
        val identity = current
        var startedDuringTheLastStep = Int.MAX_VALUE
        whenever(identity.runtime.initialize(any(), any())).doAnswer {
            startedDuringTheLastStep = startup.startedGeneration.value
            Unit
        }

        startup.onIdentityStarted()

        assertEquals(AppIdentityStartup.NOT_STARTED, startedDuringTheLastStep)
        assertEquals(0, startup.startedGeneration.value)

        holder.replace { true }
        assertEquals(0, startup.startedGeneration.value)

        current = Identity()
        startup.onIdentityStarted()
        assertEquals(1, startup.startedGeneration.value)
    }
}
