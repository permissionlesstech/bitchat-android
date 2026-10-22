package com.bitchat.android.navigation

import android.os.Build
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
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
 * Every route resolves to exactly one entry once the host's own entries and
 * every feature's Hilt-provided installers are assembled, as MainActivity does.
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
        val installers: Set<EntryProviderInstaller> = setOf(
            SettingsEntriesModule.settingsEntries(
                navigator = navigator,
                share = mock(),
                backgroundMesh = mock(),
                diagnostics = mock(),
                buildInfo = AppBuildInfo(versionName = "0.0.0", isDebug = true, githubReleaseCertSha256 = ""),
            ),
        ) + appEntries(navigator = navigator, chatState = ChatState(TestScope()), onboarding = {})

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
