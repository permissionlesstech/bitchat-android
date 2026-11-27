package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.TestMeshStores
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The session's dialogs are read from the session, whichever screen raised them, and answered
 * through the managers that raised them. Synthetic channel, peer and file only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionDialogsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val myPeerID = "aaaaaaaaaaaaaaaa"
    private val channel = "#synthetic-locked"
    private val consent = MutableStateFlow<LegacyPrivateMediaConsentRequest?>(null)
    private lateinit var state: ChatState
    private lateinit var channelManager: ChannelManager
    private lateinit var mediaSendingManager: MediaSendingManager
    private lateinit var viewModel: SessionDialogsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val application: Application = RuntimeEnvironment.getApplication()
        state = ChatState(sessionScope, TestMeshStores(application).appStateStore)
        val mesh = mock<MeshService>()
        whenever(mesh.myPeerID).thenReturn(myPeerID)
        channelManager = mock()
        mediaSendingManager = mock()
        whenever(mediaSendingManager.legacyPrivateMediaConsent).thenReturn(consent)
        viewModel = SessionDialogsViewModel(
            state = state,
            sessionMesh = ChatSessionMesh(mesh),
            channelManager = channelManager,
            mediaSendingManager = mediaSendingManager,
        )
    }

    @After
    fun tearDown() {
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a password prompt raised in the session is shown`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.uiState.collect {} }

        raisePrompt()

        assertTrue(viewModel.uiState.value.showPasswordPrompt)
        assertEquals(channel, viewModel.uiState.value.passwordPromptChannel)
    }

    @Test
    fun `a pending legacy media consent is shown`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val request = LegacyPrivateMediaConsentRequest(
            requestId = "synthetic-request",
            recipientNickname = "synthetic-peer",
            fileName = "synthetic.jpg",
            warning = "synthetic warning",
        )

        consent.value = request

        assertEquals(request, viewModel.uiState.value.legacyPrivateMediaConsent)
    }

    @Test
    fun `an accepted password joins with this peer's id and closes the prompt`() {
        raisePrompt()
        whenever(channelManager.joinChannel(channel, "synthetic-password", myPeerID)).thenReturn(true)

        viewModel.onAction(SessionDialogAction.SubmitChannelPassword(channel, "synthetic-password"))

        verify(channelManager).joinChannel(channel, "synthetic-password", myPeerID)
        assertFalse(state.showPasswordPrompt.value)
        assertNull(state.passwordPromptChannel.value)
    }

    @Test
    fun `a refused password leaves the prompt open`() {
        raisePrompt()
        whenever(channelManager.joinChannel(channel, "synthetic-password", myPeerID)).thenReturn(false)

        viewModel.onAction(SessionDialogAction.SubmitChannelPassword(channel, "synthetic-password"))

        assertTrue(state.showPasswordPrompt.value)
        assertEquals(channel, state.passwordPromptChannel.value)
    }

    @Test
    fun `dismissing closes the prompt`() {
        raisePrompt()

        viewModel.onAction(SessionDialogAction.DismissPasswordPrompt)

        assertFalse(state.showPasswordPrompt.value)
        assertNull(state.passwordPromptChannel.value)
    }

    @Test
    fun `approving and cancelling reach the media sender`() {
        viewModel.onAction(SessionDialogAction.ApproveLegacyPrivateMedia("synthetic-request-a"))
        viewModel.onAction(SessionDialogAction.CancelLegacyPrivateMedia("synthetic-request-b"))

        verify(mediaSendingManager).approveLegacyPrivateMedia("synthetic-request-a")
        verify(mediaSendingManager).cancelLegacyPrivateMedia("synthetic-request-b")
    }

    private fun raisePrompt() {
        state.setPasswordPromptChannel(channel)
        state.setShowPasswordPrompt(true)
    }
}
