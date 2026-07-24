package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChannelManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var chatState: ChatState
    private lateinit var messageManager: MessageManager
    private lateinit var dataManager: DataManager
    private lateinit var channelManager: ChannelManager

    @Before
    fun setup() {
        dataManager = DataManager(context = context)
        dataManager.clearAllData()
        chatState = ChatState(scope = testScope)
        messageManager = MessageManager(state = chatState)
        channelManager = ChannelManager(
            state = chatState,
            messageManager = messageManager,
            dataManager = dataManager,
            coroutineScope = testScope
        )
    }

    @Test
    fun setChannelPassword_persistsCommitment_andHasKey() = runTest(testDispatcher) {
        val channel = "#secret"
        chatState.setJoinedChannels(setOf(channel))
        chatState.setCurrentChannel(channel)

        channelManager.setChannelPassword(channel, "correct-horse")

        assertTrue(channelManager.hasChannelKey(channel))
        assertTrue(channelManager.isChannelPasswordProtected(channel))
        val commitment = channelManager.getChannelKeyCommitment(channel)
        assertNotNull(commitment)
        assertEquals(commitment, dataManager.loadChannelKeyCommitments()[channel])
    }

    @Test
    fun verifyChannelPassword_rejectsWrongPassword_whenCommitmentExists() = runTest(testDispatcher) {
        val channel = "#ops"
        channelManager.setChannelPassword(channel, "right-password")
        val commitment = channelManager.getChannelKeyCommitment(channel)!!
        dataManager.saveChannelKeyCommitments(mapOf(channel to commitment))

        val freshState = ChatState(scope = testScope)
        freshState.setJoinedChannels(setOf(channel))
        freshState.setPasswordProtectedChannels(setOf(channel))
        val freshManager = ChannelManager(
            freshState,
            MessageManager(freshState),
            dataManager,
            testScope
        )

        assertFalse(freshManager.hasChannelKey(channel))
        assertFalse(freshManager.verifyChannelPassword(channel, "wrong-password"))
        assertFalse(freshManager.hasChannelKey(channel))
        assertTrue(freshManager.verifyChannelPassword(channel, "right-password"))
        assertTrue(freshManager.hasChannelKey(channel))
    }

    @Test
    fun verifyChannelPassword_redecryptsPlaceholderHistory() = runTest(testDispatcher) {
        val channel = "#vault"
        val password = "shared-secret"
        val plaintext = "hello after unlock"

        channelManager.setChannelPassword(channel, password)
        val commitment = channelManager.getChannelKeyCommitment(channel)!!
        val key = deriveKeyForTest(password, channel)
        val ciphertext = encryptForTest(plaintext, key)
        dataManager.saveChannelKeyCommitments(mapOf(channel to commitment))

        val placeholder = BitchatMessage(
            id = "MSG-1",
            sender = "alice",
            content = ChannelManager.ENCRYPTED_PLACEHOLDER,
            timestamp = Date(),
            senderPeerID = "peer-a",
            channel = channel,
            encryptedContent = ciphertext,
            isEncrypted = true
        )

        val freshState = ChatState(scope = testScope)
        freshState.setJoinedChannels(setOf(channel))
        freshState.setPasswordProtectedChannels(setOf(channel))
        freshState.setChannelMessages(mapOf(channel to listOf(placeholder)))
        val freshMessages = MessageManager(freshState)
        val freshManager = ChannelManager(freshState, freshMessages, dataManager, testScope)

        assertTrue(freshManager.verifyChannelPassword(channel, password))
        assertEquals(plaintext, freshState.getChannelMessagesValue()[channel]?.first()?.content)
    }

    @Test
    fun sendEncryptedChannelMessage_emitsBinaryPayload_notFallback_whenKeyed() = runTest(testDispatcher) {
        val channel = "#enc"
        channelManager.setChannelPassword(channel, "pw")

        val latch = CountDownLatch(1)
        val payload = AtomicReference<ByteArray?>(null)
        var fallbackCalled = false

        channelManager.sendEncryptedChannelMessage(
            content = "secret text",
            mentions = emptyList(),
            channel = channel,
            senderNickname = "me",
            myPeerID = "peer-me",
            onEncryptedPayload = {
                payload.set(it)
                latch.countDown()
            },
            onFallback = {
                fallbackCalled = true
                latch.countDown()
            }
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertFalse(fallbackCalled)
        assertNotNull(payload.get())
        val parsed = BitchatMessage.fromBinaryPayload(payload.get()!!)
        assertNotNull(parsed)
        assertTrue(parsed!!.isEncrypted)
        assertEquals(channel, parsed.channel)
        assertTrue(parsed.encryptedContent?.isNotEmpty() == true)
    }

    @Test
    fun sendEncryptedChannelMessage_callsFallback_whenNoKey() {
        val latch = CountDownLatch(1)
        var fallbackCalled = false
        var payloadCalled = false

        channelManager.sendEncryptedChannelMessage(
            content = "should not send",
            mentions = emptyList(),
            channel = "#nokey",
            senderNickname = "me",
            myPeerID = "peer-me",
            onEncryptedPayload = {
                payloadCalled = true
                latch.countDown()
            },
            onFallback = {
                fallbackCalled = true
                latch.countDown()
            }
        )

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(fallbackCalled)
        assertFalse(payloadCalled)
    }

    @Test
    fun joinChannel_requiresPassword_whenProtectedWithoutKey() = runTest(testDispatcher) {
        val channel = "#locked"
        channelManager.setChannelPassword(channel, "real-pw")
        val commitment = channelManager.getChannelKeyCommitment(channel)!!
        dataManager.saveChannelKeyCommitments(mapOf(channel to commitment))

        val freshState = ChatState(scope = testScope)
        freshState.setPasswordProtectedChannels(setOf(channel))
        val freshManager = ChannelManager(
            freshState,
            MessageManager(freshState),
            dataManager,
            testScope
        )

        assertFalse(freshManager.joinChannel(channel, null, "peer-1"))
        assertTrue(freshState.getShowPasswordPromptValue())
        assertFalse(freshManager.joinChannel(channel, "bad", "peer-1"))
        assertTrue(freshManager.joinChannel(channel, "real-pw", "peer-1"))
        assertTrue(freshManager.hasChannelKey(channel))
    }

    private fun deriveKeyForTest(password: String, channel: String): SecretKeySpec {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            channel.toByteArray(Charsets.UTF_8),
            100_000,
            256
        )
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encryptForTest(plaintext: String, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + encrypted
    }
}
