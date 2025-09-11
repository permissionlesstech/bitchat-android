package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Spy
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.refEq
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class CommandProcessorTest() {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val meshService = BluetoothMeshService(context = context)
  private val chatState = ChatState()
  private lateinit var commandProcessor: CommandProcessor
  @Spy
  val messageManager: MessageManager = Mockito.spy(MessageManager(state = chatState))

  @Spy
  val channelManager: ChannelManager = Mockito.spy(
    ChannelManager(
      state = chatState,
      messageManager = messageManager,
      dataManager = DataManager(context = context),
      coroutineScope = CoroutineScope(StandardTestDispatcher())
    )
  )

  @Before
  fun setup() {
    commandProcessor = CommandProcessor(
      state = chatState,
      messageManager = messageManager,
      channelManager = channelManager,
      privateChatManager = PrivateChatManager(
        state = chatState,
        messageManager = messageManager,
        dataManager = DataManager(context = context),
        noiseSessionDelegate = mock<NoiseSessionDelegate>()
      )
    )
  }

  @Test
  fun `when using lower case join command, user is correctly added to channel`() {
    val channel = "channel-1"
    val expectedMessage = BitchatMessage(
      sender = "system",
      content = "joined channel #$channel",
      timestamp = Date(),
      isRelay = false
    )

    val result = commandProcessor.processCommand(
        command = "/j $channel",
        meshService = meshService,
        myPeerID = "peer-id",
        onSendMessage = { a, b, c -> { } },
        viewModel = null
    )

    assertEquals(result, true)
    verify(messageManager).addMessage(refEq(expectedMessage, "timestamp", "id"))
  }

  @Test
  fun `when using upper case join command, user is correctly added to channel`() {
    val channel = "channel-1"
    val expectedMessage = BitchatMessage(
      sender = "system",
      content = "joined channel #$channel",
      timestamp = Date(),
      isRelay = false
    )

    val result = commandProcessor.processCommand(
      command = "/JOIN $channel",
      meshService = meshService,
      myPeerID = "peer-id",
      onSendMessage = { a, b, c -> { } },
      viewModel = null
    )

    assertEquals(result, true)
    verify(messageManager).addMessage(refEq(expectedMessage, "timestamp", "id"))
  }

  @Test
  fun `when unknown command lower case is given, channel is not joined`() {
    val channel = "channel-1"

    val result = commandProcessor.processCommand(
      command = "/wtfjoin $channel", meshService = meshService, myPeerID = "peer-id",
      onSendMessage = { a, b, c -> { } }, viewModel = null
    )

    assertEquals(result, true)
    verify(channelManager, never()).joinChannel(eq("#$channel"), anyOrNull(), eq("peer-id"))
  }
}
