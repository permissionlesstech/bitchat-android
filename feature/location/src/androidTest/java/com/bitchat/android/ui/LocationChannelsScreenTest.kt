package com.bitchat.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.feature.location.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.net.TorMode
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NoteSendResult
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The location channels screen on synthetic state. No location service is involved: the screen
 * test draws a preview state, and the Root test runs the ViewModel over inert fake ports.
 */
class LocationChannelsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun meshRow_showsMeshTitle_andTapSendsMeshClicked() {
        val meshTitle = context.getString(R.string.mesh_title)
        val actions = mutableListOf<LocationChannelsAction>()

        compose.setContent {
            BitchatTheme {
                LocationChannelsScreen(
                    uiState = previewLocationChannelsUiState(),
                    customGeohash = "",
                    customError = null,
                    onCustomGeohashChange = {},
                    onAction = { actions += it },
                )
            }
        }

        compose.onAllNodesWithText(meshTitle).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Synthetic City", substring = true).onFirst().assertIsDisplayed()
        compose.onNode(hasText(meshTitle) and hasClickAction()).performClick()
        compose.waitForIdle()

        // The bookmark row also reports itself (BookmarkShown); the tap adds exactly MeshClicked.
        assertEquals(
            listOf<LocationChannelsAction>(LocationChannelsAction.MeshClicked),
            actions.filterNot { it is LocationChannelsAction.BookmarkShown },
        )
    }

    /** Review Focus 4, Q-L2: a rejected map pick lands in the field, with the error under it. */
    @Test
    fun customGeohash_invalid_rewritesFieldAndShowsError() {
        val viewModel = LocationChannelsViewModel(
            InertLocation(), InertNotes(), InertGate(), InertBookmarks(), InertTor(), InertSession(),
            Dispatchers.Default,
        )
        // Owned by a store, so clearing it at the end stops its sampling collector.
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
            },
        )[LocationChannelsViewModel::class.java]
        compose.setContent {
            BitchatTheme {
                LocationChannelsRoot(onClose = {}, onShowLocationNotes = {}, viewModel = viewModel)
            }
        }

        // What the Root's picker callback does with a picked geohash.
        compose.runOnUiThread {
            viewModel.onAction(LocationChannelsAction.CustomGeohashSubmitted(" #Not-A-Geohash "))
        }
        val error = context.getString(R.string.invalid_geohash)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(error))

        try {
            compose.onAllNodesWithText("not-a-geohash").onFirst().assertIsDisplayed()
            compose.onAllNodesWithText(error).onFirst().assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }

    private class InertLocation : NotesLocationSource {
        override val permissionState = MutableStateFlow(PermissionState.AUTHORIZED)
        override val effectiveLocationEnabled = MutableStateFlow(true)
        override val availableChannels = MutableStateFlow(
            listOf(GeohashChannel(GeohashChannelLevel.CITY, "s0000"))
        )
        override val isLoadingLocation = MutableStateFlow(false)
        override val locationNames = MutableStateFlow(emptyMap<GeohashChannelLevel, String>())
        override val selectedChannel = MutableStateFlow<ChannelID>(ChannelID.Mesh)
        override val locationServicesEnabled = MutableStateFlow(true)
        override val systemLocationEnabled = MutableStateFlow(true)
        override fun refreshChannels() = Unit
        override fun enableLocationServices() = Unit
        override fun disableLocationServices() = Unit
        override fun enableLocationChannels() = Unit
        override fun syncPermissionState() = permissionState.value
        override fun beginLiveRefresh() = Unit
        override fun endLiveRefresh() = Unit
        override fun select(channel: ChannelID) = Unit
        override fun selectNearby(channel: GeohashChannel) = false
        override fun selectManual(channel: GeohashChannel, teleported: Boolean) = Unit
    }

    private class InertNotes : NotesStore {
        override val notes = MutableStateFlow(emptyList<LocationNotesManager.Note>())
        override val state = MutableStateFlow(LocationNotesManager.State.READY)
        override val errorMessage = MutableStateFlow<String?>(null)
        override val initialLoadComplete = MutableStateFlow(true)
        override suspend fun send(content: String, nickname: String?) = NoteSendResult.Sent
        override fun refresh() = Unit
        override fun clearError() = Unit
    }

    private class InertGate : NearbyNotesGate {
        override val revealed = MutableStateFlow(false)
        override fun updateAvailability(enabled: Boolean, authorized: Boolean, geohash: String?) = Unit
        override fun activate() = Unit
        override fun deactivate() = Unit
        override fun reveal() = Unit
    }

    private class InertBookmarks : ChannelBookmarks {
        override val bookmarks = MutableStateFlow(emptyList<String>())
        override val bookmarkNames = MutableStateFlow(emptyMap<String, String>())
        override fun toggle(geohash: String) = Unit
        override fun resolveNameIfNeeded(geohash: String) = Unit
    }

    private class InertTor : TorRouting {
        override val mode = MutableStateFlow(TorMode.ON)
        override fun isTorAvailable() = true
        override fun setMode(mode: TorMode) = Unit
    }

    private class InertSession : ChannelsSession {
        override val participantCounts = MutableStateFlow(emptyMap<String, Int>())
        override val wifiAwareEnabled = MutableStateFlow(false)
        override val connectedPeers = MutableStateFlow(emptyList<String>())
        override val myPeerID = "0123456789abcdef"
        override fun beginGeohashSampling(live: Collection<String>, userSelected: Collection<String>) = Unit
        override fun endGeohashSampling() = Unit
    }
}
