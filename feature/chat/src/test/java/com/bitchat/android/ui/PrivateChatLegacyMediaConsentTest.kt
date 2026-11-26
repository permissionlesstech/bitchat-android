package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.services.TestMeshStores
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Private media is sent only from the private chat, so the one-shot consent to send it to an
 * older peer unencrypted is asked there too: chat, the other screen that asks, is not composed
 * under the private chat. Synthetic peers only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivateChatLegacyMediaConsentTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val thisPeerID = "0102030405060708"
    private val otherPeerID = "1112131415161718"
    private val consent = MutableStateFlow<LegacyPrivateMediaConsentRequest?>(null)
    private lateinit var mediaSendingManager: MediaSendingManager
    private lateinit var viewModel: PrivateChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val application: Application = RuntimeEnvironment.getApplication()
        val stores = TestMeshStores(application)
        val state = ChatState(sessionScope, stores.appStateStore)
        val mesh = mock<MeshService>()
        whenever(mesh.myPeerID).thenReturn("aaaaaaaaaaaaaaaa")
        val privateChatSession = mock<PrivateChatSession>()
        wheneverBlocking { privateChatSession.start(any()) }.thenReturn(true)
        val verificationHandler = mock<VerificationHandler>()
        whenever(verificationHandler.verifiedFingerprints).thenReturn(MutableStateFlow(emptySet()))
        whenever(verificationHandler.resolvePeerDisplayNameForFingerprint(any()))
            .thenReturn("synthetic-peer")
        val wifiAwarePeers = object : WifiAwarePeers {
            override val connected = MutableStateFlow<Map<String, *>>(emptyMap<String, Any>())
            override val enabled = MutableStateFlow(false)
        }
        mediaSendingManager = mock()
        whenever(mediaSendingManager.legacyPrivateMediaConsent).thenReturn(consent)
        viewModel = PrivateChatViewModel(
            routeConversationID = thisPeerID,
            state = state,
            sessionMesh = ChatSessionMesh(mesh),
            privateChatSession = privateChatSession,
            messageSender = mock(),
            composerMedia = mock(),
            mediaSendingManager = mediaSendingManager,
            contactFavorites = mock(),
            verificationHandler = verificationHandler,
            geohashSession = mock(),
            conversationListPreferences = mock(),
            wifiAwarePeers = wifiAwarePeers,
            contactDirectory = stores.contactDirectory,
            favorites = Provider { stores.favorites },
            geohashConversations = GeohashConversationRegistry(),
            geohashAliases = stores.geohashAliases,
        )
    }

    @After
    fun tearDown() {
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a consent pending for this chat is offered here`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val request = request(thisPeerID)

        consent.value = request

        assertEquals(request, viewModel.uiState.value.legacyPrivateMediaConsent)
    }

    @Test
    fun `a consent pending for another chat is not offered here`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.uiState.collect {} }

        consent.value = request(otherPeerID)

        assertNull(viewModel.uiState.value.legacyPrivateMediaConsent)
    }

    @Test
    fun `approving and cancelling here reach the media sender`() {
        viewModel.onAction(PrivateChatAction.ApproveLegacyPrivateMedia("synthetic-request-a"))
        viewModel.onAction(PrivateChatAction.CancelLegacyPrivateMedia("synthetic-request-b"))

        verify(mediaSendingManager).approveLegacyPrivateMedia("synthetic-request-a")
        verify(mediaSendingManager).cancelLegacyPrivateMedia("synthetic-request-b")
    }

    private fun request(conversationID: String) = LegacyPrivateMediaConsentRequest(
        requestId = "synthetic-request",
        conversationID = conversationID,
        recipientNickname = "synthetic-peer",
        fileName = "synthetic.jpg",
        warning = "synthetic warning",
    )
}
