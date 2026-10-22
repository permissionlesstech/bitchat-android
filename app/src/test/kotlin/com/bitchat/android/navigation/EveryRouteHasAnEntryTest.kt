package com.bitchat.android.navigation

import android.os.Build
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.bitchat.android.chat.ChatEntriesModule
import com.bitchat.android.location.LocationEntriesModule
import com.bitchat.android.settings.SettingsEntriesModule
import com.bitchat.android.share.AppBuildInfo
import com.bitchat.android.ui.ChatState
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every route in [routes] resolves to exactly one entry once the host's own
 * entries and the feature installers are assembled.
 *
 * This does not exercise the Hilt graph. It assembles the installers by calling
 * the three providers by hand, so a feature module that is missing from the
 * graph (not a dependency of :app, or lacking @InstallIn) is not caught, and
 * neither is a route missing from the hand-written [routes] list.
 *
 * Builds the installers the way Hilt would, by calling each provider with fakes,
 * then resolves each route through Nav3's own [entryProvider]: an unknown key
 * hits the fallback, and a route two installers both claim throws while the
 * provider is being built. No entry's content is composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class EveryRouteHasAnEntryTest {

    private val routes: List<NavKey> = listOf(
        OnboardingRoute,
        ChatRoute,
        AboutRoute,
        LocationNotesRoute,
        ChatUserRoute(nickname = "alice", messageId = null),
        VerificationRoute(peerID = "0123456789abcdef"),
        LocationChannelsRoute,
        DebugSettingsRoute,
        MeshPeerListRoute,
        PrivateChatRoute(conversationID = "0123456789abcdef"),
        SecurityVerificationRoute(conversationID = "0123456789abcdef"),
    )

    @Test
    fun `every route resolves to an entry`() {
        val navigator = AppNavigator()
        val chatState = ChatState(TestScope())
        val installers: Set<EntryProviderInstaller> = setOf(
            SettingsEntriesModule.settingsEntries(
                navigator = navigator,
                share = mock(),
                backgroundMesh = mock(),
                diagnostics = mock(),
                buildInfo = AppBuildInfo(versionName = "0.0.0", isDebug = true, githubReleaseCertSha256 = ""),
            ),
            LocationEntriesModule.locationEntries(navigator = navigator, chatState = chatState),
            ChatEntriesModule.chatEntries(navigator = navigator, chatState = chatState),
        ) + appEntries(onboarding = {})

        val unresolved = mutableListOf<NavKey>()
        val provider = entryProvider<NavKey>(
            fallback = { key -> unresolved += key; error("no entry for $key") },
        ) {
            installers.forEach { install -> install() }
        }

        routes.forEach { route -> runCatching { provider(route) } }

        assertEquals("routes with no entry", emptyList<NavKey>(), unresolved)
    }
}
