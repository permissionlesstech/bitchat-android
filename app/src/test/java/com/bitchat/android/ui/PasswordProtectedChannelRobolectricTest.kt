package com.bitchat.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.AppStateStore
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No encrypted channel format exists, so a password-protected channel cannot carry messages and
 * no password can be checked. Both must fail closed and visibly, and nothing may fall back to
 * plaintext on the mesh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PasswordProtectedChannelRobolectricTest {

    private val channel = "#synthetic-channel"
    private val myPeerID = "0011223344556677"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val state = ChatState(scope)
    private val messages = MessageManager(state)
    private val mesh: MeshService = mock { on { myPeerID } doReturn myPeerID }
    private val forwarded = mutableListOf<Pair<String, String?>>()
    private lateinit var channels: ChannelManager
    private lateinit var commands: CommandProcessor

    @Before
    fun setUp() {
        AppStateStore.clear()
        val context = ApplicationProvider.getApplicationContext<Application>()
        channels = ChannelManager(state, messages, DataManager(context), scope)
        commands = CommandProcessor(
            state,
            messages,
            channels,
            PrivateChatManager(state, messages, DataManager(context), mock<NoiseSessionDelegate>())
        )
        state.setNickname("synthetic-nick")
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppStateStore.clear()
    }

    private fun process(command: String) {
        commands.processCommand(
            command,
            mesh,
            myPeerID,
            { content, _, target -> forwarded += content to target },
            viewModel = null
        )
    }

    private fun joinAndProtect() {
        assertTrue(channels.joinChannel(channel, null, myPeerID))
        process("/pass synthetic-password")
    }

    /** A protected channel whose key was not restored, which is the state after an app restart. */
    private fun restoreProtectedChannelWithoutKey() {
        state.setJoinedChannels(setOf(channel))
        state.setPasswordProtectedChannels(setOf(channel))
        state.setCurrentChannel(channel)
    }

    private fun timeline() = state.getChannelMessagesValue()[channel].orEmpty()

    private fun userMessages() = timeline().filter { it.sender != "system" }

    private fun assertRefusedVisibly() {
        assertTrue("nothing may reach the mesh: $forwarded", forwarded.isEmpty())
        assertTrue("no local echo for an unsent message", userMessages().isEmpty())
        assertTrue(timeline().last().content.startsWith("not sent:"))
    }

    @Test
    fun `action command to a protected channel is refused visibly`() {
        joinAndProtect()

        process("/hug synthetic-peer")

        assertRefusedVisibly()
    }

    @Test
    fun `action command to a protected channel without a restored key is not sent as plaintext`() {
        restoreProtectedChannelWithoutKey()

        process("/slap synthetic-peer")

        assertRefusedVisibly()
    }

    @Test
    fun `public cashu token to a protected channel is refused without a sent notice`() {
        joinAndProtect()
        val token = "cashuA" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"token":[{"proofs":[{"amount":1}]}],"unit":"sat"}"""
                .toByteArray(StandardCharsets.UTF_8)
        )

        process("/pay $token public")

        assertRefusedVisibly()
        assertTrue(timeline().none { it.content.startsWith("sent ") })
    }

    @Test
    fun `media to a protected channel is refused visibly and not broadcast`() {
        joinAndProtect()
        val file = kotlin.io.path.createTempFile("synthetic-media", ".jpg").toFile()
        try {
            file.writeBytes(ByteArray(64) { it.toByte() })
            val media = MediaSendingManager(
                state,
                messages,
                channels,
                scope,
                mediaWorkDispatcher = Dispatchers.Unconfined,
                getMeshService = { mesh }
            )

            media.sendImageNote(null, channel, file.absolutePath)

            verify(mesh, never()).sendFileBroadcast(any())
            assertRefusedVisibly()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `action command to an unprotected channel is still sent`() {
        assertTrue(channels.joinChannel(channel, null, myPeerID))

        process("/hug synthetic-peer")

        assertEquals(listOf(channel), forwarded.map { it.second })
        assertEquals(1, userMessages().size)
    }

    @Test
    fun `joining a protected channel without a password raises the prompt`() {
        state.setPasswordProtectedChannels(setOf(channel))

        assertFalse(channels.joinChannel(channel, null, myPeerID))

        assertTrue(state.getShowPasswordPromptValue())
        assertEquals(channel, state.getPasswordPromptChannelValue())
    }

    @Test
    fun `a password for a new protected channel is refused and closes the prompt visibly`() {
        state.setPasswordProtectedChannels(setOf(channel))
        channels.joinChannel(channel, null, myPeerID)

        val joined = channels.joinChannel(channel, "any-password", myPeerID)

        assertFalse(joined)
        assertFalse(state.getJoinedChannelsValue().contains(channel))
        assertNull(state.getCurrentChannelValue())
        assertFalse(state.getShowPasswordPromptValue())
        assertNull(state.getPasswordPromptChannelValue())
        assertTrue(state.getMessagesValue().last().content.contains(channel))
    }

    @Test
    fun `a refused password is reported in the channel the user is viewing`() {
        val viewing = "#synthetic-viewing"
        assertTrue(channels.joinChannel(viewing, null, myPeerID))
        state.setPasswordProtectedChannels(setOf(channel))

        assertFalse(channels.joinChannel(channel, "any-password", myPeerID))

        assertEquals(viewing, state.getCurrentChannelValue())
        assertTrue(state.getChannelMessagesValue()[viewing].orEmpty().last().content.contains(channel))
    }

    @Test
    fun `a password for an already joined protected channel is refused`() {
        restoreProtectedChannelWithoutKey()
        state.setCurrentChannel(null)

        assertFalse(channels.joinChannel(channel, "any-password", myPeerID))

        assertNull(state.getCurrentChannelValue())
        assertTrue(state.getMessagesValue().last().content.contains(channel))
    }
}
