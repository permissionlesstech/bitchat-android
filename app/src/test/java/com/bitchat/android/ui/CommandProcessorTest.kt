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

  @Test
  fun `text set to an inserted mention hides both popups`() {
    // A command popup is open while the user taps a nickname in the message list.
    commandProcessor.updateCommandSuggestions("/")
    assertTrue(chatState.getShowCommandSuggestionsValue())

    // The composer re-syncs both popups with the inserted text, which ends in a
    // space, so neither popup matches it.
    commandProcessor.updateCommandSuggestions("/ @alice ")
    commandProcessor.updateMentionSuggestions("/ @alice ", meshService, viewModel = null)
    assertFalse(chatState.getShowCommandSuggestionsValue())
    assertTrue(chatState.getCommandSuggestionsValue().isEmpty())
    assertFalse(chatState.getShowMentionSuggestionsValue())
    assertTrue(chatState.getMentionSuggestionsValue().isEmpty())
  }

  @Test
  fun `a mention followed by a space no longer matches the mention popup`() {
    whenever(meshService.myPeerID).thenReturn("me")
    whenever(meshService.getPeerNicknames()).thenReturn(mapOf("peer-1" to "alice"))

    commandProcessor.updateMentionSuggestions("@a", meshService, viewModel = null)
    assertTrue(chatState.getShowMentionSuggestionsValue())

    commandProcessor.updateMentionSuggestions("@alice ", meshService, viewModel = null)
    assertFalse(chatState.getShowMentionSuggestionsValue())
    assertTrue(chatState.getMentionSuggestionsValue().isEmpty())
  }

  @Test
  fun `command suggestions close once the input grows past the command`() {
    // The command popup only shows while the whole input is a prefix of a
    // command name or alias, and none of them contains a space or an @. An
    // input that can match the mention popup has already closed the command
    // popup.
    commandProcessor.updateCommandSuggestions("/m")
    assertTrue(chatState.getShowCommandSuggestionsValue())

    commandProcessor.updateCommandSuggestions("/msg @ali")
    assertFalse(chatState.getShowCommandSuggestionsValue())
    assertTrue(chatState.getCommandSuggestionsValue().isEmpty())
  }

  @Test
  fun `a restored command draft reopens the command popup`() {
    // Switching conversations restores the draft in code; re-syncing with the
    // restored text brings the popup back for a command draft.
    commandProcessor.updateCommandSuggestions("/j")
    assertTrue(chatState.getShowCommandSuggestionsValue())
    assertTrue(chatState.getCommandSuggestionsValue().isNotEmpty())

    // An empty restored draft leaves both popups hidden.
    commandProcessor.updateCommandSuggestions("")
    commandProcessor.updateMentionSuggestions("", meshService, viewModel = null)
    assertFalse(chatState.getShowCommandSuggestionsValue())
    assertFalse(chatState.getShowMentionSuggestionsValue())
  }
}
