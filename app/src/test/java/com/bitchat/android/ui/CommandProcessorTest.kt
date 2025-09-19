package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import junit.framework.TestCase.assertEquals

import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class CommandProcessorTest() {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val chatState = ChatState()
  private lateinit var commandProcessor: CommandProcessor

  val messageManager: MessageManager = MessageManager(state = chatState)
  val channelManager: ChannelManager = ChannelManager(
    state = chatState,
    messageManager = messageManager,
    dataManager = DataManager(context = context),
    coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)
  )

  private val meshService: BluetoothMeshService = mock()

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
}
