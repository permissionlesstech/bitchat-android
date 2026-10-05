package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import com.bitchat.android.services.AppStateStore
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
class CommandProcessorTest {
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
    AppStateStore.clear()
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

  @After
  fun tearDown() {
    AppStateStore.clear()
  }

  @Test
  fun `clear geohash removes only selected history from state and store`() {
    val selected = "s000"
    val other = "s001"
    val message = BitchatMessage(sender = "synthetic", content = "history", timestamp = Date(1))
    messageManager.addMessage(message.copy(id = "public"))
    messageManager.addChannelMessage("geo:$selected", message.copy(id = "selected"))
    messageManager.addChannelMessage("geo:$other", message.copy(id = "other"))
    chatState.setSelectedLocationChannel(
      ChannelID.Location(GeohashChannel(GeohashChannelLevel.PROVINCE, selected))
    )
    // A stale classic channel must not override the currently visible geohash.
    chatState.setCurrentChannel("#previous")

    execute("/clear")

    assertTrue(chatState.getChannelMessagesValue()["geo:$selected"].orEmpty().isEmpty())
    assertTrue(AppStateStore.channelMessages.value["geo:$selected"].orEmpty().isEmpty())
    assertEquals(1, chatState.getMessagesValue().size)
    assertEquals(1, AppStateStore.publicMessages.value.size)
    assertEquals(1, chatState.getChannelMessagesValue()["geo:$other"]?.size)
    assertEquals(1, AppStateStore.channelMessages.value["geo:$other"]?.size)
  }

  @Test
  fun `clear mesh preserves joined channels and prevents snapshot resurrection`() {
    val message = BitchatMessage(sender = "synthetic", content = "history", timestamp = Date(1))
    messageManager.addMessage(message.copy(id = "mesh"))
    messageManager.addChannelMessage("#kept", message.copy(id = "kept"))

    execute("/clear")

    assertTrue(chatState.getMessagesValue().isEmpty())
    assertTrue(AppStateStore.publicMessages.value.isEmpty())
    assertEquals(1, chatState.getChannelMessagesValue()["#kept"]?.size)
    assertEquals(1, AppStateStore.channelMessages.value["#kept"]?.size)
  }

  @Test
  fun `command feedback stays in selected classic channel`() {
    chatState.setCurrentChannel("#synthetic")
    execute("/channels")
    execute("/unknown")
    execute("/msg")
    execute("/unblock")
    assertEquals(4, chatState.getChannelMessagesValue()["#synthetic"]?.size)
    assertTrue(chatState.getMessagesValue().isEmpty())
  }

  @Test
  fun `command feedback stays in selected geohash`() {
    chatState.setSelectedLocationChannel(
      ChannelID.Location(GeohashChannel(GeohashChannelLevel.PROVINCE, "s000"))
    )
    execute("/w")
    execute("/unknown")
    assertEquals(2, chatState.getChannelMessagesValue()["geo:s000"]?.size)
    assertTrue(chatState.getMessagesValue().isEmpty())
  }

  @Test
  fun `command feedback stays in selected private chat without marking unread`() {
    val peer = "0102030405060708"
    chatState.setSelectedPrivateChatPeer(peer)
    execute("/unknown")
    assertEquals(1, chatState.getPrivateChatsValue()[peer]?.size)
    assertTrue(chatState.getUnreadPrivateMessagesValue().isEmpty())
    assertTrue(chatState.getMessagesValue().isEmpty())
  }

  @Test
  fun `join help includes optional password in usage and suggestions`() {
    execute("/join")
    assertEquals("usage: /join <channel> [password]", chatState.getMessagesValue().single().content)
    commandProcessor.updateCommandSuggestions("/j")
    assertEquals("<channel> [password]", chatState.getCommandSuggestionsValue().first().syntax)
  }

  private fun execute(command: String) {
    commandProcessor.processCommand(command, meshService, "self", { _, _, _ -> })
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

  @Test
  fun `pay feedback is added to active geohash channel`() {
    val geohash = "u0nd"
    chatState.setSelectedLocationChannel(
      ChannelID.Location(GeohashChannel(GeohashChannelLevel.PROVINCE, geohash))
    )

    commandProcessor.processCommand(
      command = "/pay invalid",
      meshService = meshService,
      myPeerID = "peer-id",
      onSendMessage = { _, _, _ -> },
      viewModel = null
    )

    assertEquals(
      "invalid cashu token — not sending it",
      chatState.getChannelMessagesValue()["geo:$geohash"]?.single()?.content
    )
    assertEquals(0, chatState.getMessagesValue().size)
  }

  @Test
  fun `join command leaves active geohash selection for mesh channel routing`() {
    chatState.setSelectedLocationChannel(
      ChannelID.Location(GeohashChannel(GeohashChannelLevel.REGION, "9q"))
    )

    commandProcessor.processCommand(
      command = "/join backchannel",
      meshService = meshService,
      myPeerID = "peer-id",
      onSendMessage = { _, _, _ -> },
      viewModel = null
    )

    assertEquals("#backchannel", chatState.getCurrentChannelValue())
    assertEquals(ChannelID.Mesh, chatState.selectedLocationChannel.value)
  }

  @Test
  fun `clearSuggestions hides the command suggestion popup`() {
    // Typing "/" opens the command popup.
    commandProcessor.updateCommandSuggestions("/")
    assertTrue(chatState.getShowCommandSuggestionsValue())

    // The send handlers call this after clearing the field in code, which does
    // not run the text-change handler that normally hides the popup.
    commandProcessor.clearSuggestions()
    assertFalse(chatState.getShowCommandSuggestionsValue())
    assertTrue(chatState.getCommandSuggestionsValue().isEmpty())
  }

  @Test
  fun `clearSuggestions hides the mention suggestion popup`() {
    whenever(meshService.getPeerNicknames()).thenReturn(mapOf("peer-1" to "alice"))

    // Typing "@a" opens the mention popup.
    commandProcessor.updateMentionSuggestions("@a", meshService, viewModel = null)
    assertTrue(chatState.getShowMentionSuggestionsValue())

    commandProcessor.clearSuggestions()
    assertFalse(chatState.getShowMentionSuggestionsValue())
    assertTrue(chatState.getMentionSuggestionsValue().isEmpty())

  }
}
