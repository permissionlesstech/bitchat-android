package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class CommandProcessorTest() {
  private val context: Context = ApplicationProvider.getApplicationContext()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
  private val chatState = ChatState(scope = testScope)
  private lateinit var commandProcessor: CommandProcessor

  val messageManager: MessageManager = MessageManager(state = chatState)
  val channelManager: ChannelManager = ChannelManager(
    state = chatState,
    messageManager = messageManager,
    dataManager = DataManager(context = context),
    coroutineScope = testScope
  )

  private val meshService: MeshService = mock()

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

  @Ignore // Temporarily disabled due to Mockito final class issues
  @Test
  fun `when using lower case join command, command returns true`() {
    val channel = "channel-1"

    val result = commandProcessor.processCommand(
        command = "/j $channel",
        meshService = meshService,
        myPeerID = "peer-id",
        onSendMessage = { a, b, c -> { } },
        viewModel = null
    )

    assertEquals(result, true)
  }

  @Ignore // Temporarily disabled due to Mockito final class issues
  @Test
  fun `when using upper case join command, command returns true`() {
    val channel = "channel-1"

    val result = commandProcessor.processCommand(
      command = "/JOIN $channel",
      meshService = meshService,
      myPeerID = "peer-id",
      onSendMessage = { a, b, c -> { } },
      viewModel = null
    )

    assertEquals(result, true)
  }

  @Ignore // Temporarily disabled due to Mockito final class issues
  @Test
  fun `when unknown command lower case is given, command returns true but does not process special handling`() {
    val channel = "channel-1"

    val result = commandProcessor.processCommand(
      command = "/wtfjoin $channel", meshService = meshService, myPeerID = "peer-id",
      onSendMessage = { a, b, c -> { } }, viewModel = null
    )

    assertEquals(result, true)
  }

  @Test
  fun `msg command persists incoming messages as locally read through shared chat opening`() {
    val peerID = "0102030405060708"
    val message = BitchatMessage(
      id = "message-opened-by-command",
      sender = "alice",
      content = "hello",
      timestamp = Date(1),
      isPrivate = true,
      senderPeerID = peerID
    )
    val locallyRead = mutableListOf<String>()
    chatState.setPrivateChats(mapOf(peerID to listOf(message)))
    whenever(meshService.getPeerNicknames()).thenReturn(mapOf(peerID to "alice"))

    commandProcessor = CommandProcessor(
      state = chatState,
      messageManager = messageManager,
      channelManager = channelManager,
      privateChatManager = PrivateChatManager(
        state = chatState,
        messageManager = messageManager,
        dataManager = DataManager(context = context),
        noiseSessionDelegate = mock<NoiseSessionDelegate>(),
        markMessageReadLocally = locallyRead::add
      )
    )

    commandProcessor.processCommand(
      command = "/msg alice",
      meshService = meshService,
      myPeerID = "self",
      onSendMessage = { _, _, _ -> },
      viewModel = null
    )

    assertTrue(locallyRead.contains(message.id))
  }
}
