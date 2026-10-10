package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.media.WaveformPreview
import com.bitchat.android.ui.theme.BitchatTheme
import java.lang.reflect.Proxy
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.ui.theme.ChatUiMode
import com.bitchat.android.ui.theme.ChatUiModeManager

class ChatUiParityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun waveformReloadsWhenBothOldAndNewSourcesAreUncached() {
        var path by mutableStateOf("live")
        var live by mutableStateOf(false)
        val old = CompletableDeferred<List<Float>?>()
        val loaded = mutableListOf<String>()
        compose.setContent {
            BitchatTheme {
                WaveformPreview(
                    Modifier.fillMaxWidth().height(32.dp), path, null, null,
                    isLive = live,
                    loadSamples = { source ->
                        loaded.add(source)
                        if (source == "live") old.await() else List(120) { 0.6f }
                    },
                )
            }
        }
        compose.onNode(hasStateDescription("Loading waveform")).assertExists()
        compose.runOnIdle { path = "finished" }
        compose.onNode(hasStateDescription("Audio waveform")).assertExists()
        compose.runOnIdle { old.complete(List(120) { 0f }) }
        compose.onNode(hasStateDescription("Audio waveform")).assertExists()
        assertEquals(listOf("live", "finished"), loaded)
        compose.runOnIdle { live = true }
        compose.onNode(hasStateDescription("Live audio")).assertExists()
        compose.runOnIdle { live = false }
        compose.onNode(hasStateDescription("Audio waveform")).assertExists()
        assertEquals(listOf("live", "finished", "finished"), loaded)
    }

    @Test fun privateTimelineWiresMessageActionsAndJumpToLatest() {
        val messages = (0..35).map { index ->
            BitchatMessage(id = "synthetic-$index", sender = "alice", content = "Synthetic message $index",
                senderPeerID = "synthetic-other", timestamp = Date(index * 60_000L), isPrivate = true)
        }
        compose.setContent {
            BitchatTheme {
                Surface {
                    ConversationTimelineContent(
                        messages, "me", syntheticMesh(), ConversationUiContext("dm", "synthetic-other"),
                        onMention = {}, onCancelTransfer = {}, modifier = Modifier.fillMaxSize(),
                        messageActions = { message, dismiss ->
                            AlertDialog(onDismissRequest = dismiss, text = { Text(message.content) },
                                confirmButton = { TextButton(onClick = dismiss) { Text("Close actions") } })
                        },
                    )
                }
            }
        }
        compose.onNode(hasText("Synthetic message 35", substring = true)).performTouchInput { longClick() }
        compose.onNodeWithText("Close actions").assertExists().performClick()
        compose.onNode(hasScrollAction()).performScrollToIndex(30)
        compose.onNodeWithContentDescription("Scroll to bottom").assertExists().performClick()
        compose.onNode(hasText("Synthetic message 35", substring = true)).assertIsDisplayed()
    }

    @Test fun everyConversationOpensImagesAndMatrixImagesSupportLongPress() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(app.cacheDir, "synthetic-gallery.png")
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var context by mutableStateOf(ConversationUiContext("mesh"))
        compose.setContent {
            BitchatTheme {
                Surface {
                    ConversationTimelineContent(
                        listOf(BitchatMessage(id = "synthetic-image", sender = "alice", content = file.path,
                            type = BitchatMessageType.Image, timestamp = Date(0), senderPeerID = "synthetic-other",
                            isPrivate = context.privatePeerID != null)),
                        "me", syntheticMesh(), context, onMention = {}, onCancelTransfer = {},
                        modifier = Modifier.fillMaxSize(),
                        messageActions = { _, dismiss ->
                            AlertDialog(onDismissRequest = dismiss, text = { Text("Image actions") },
                                confirmButton = { TextButton(onClick = dismiss) { Text("Close actions") } })
                        },
                    )
                }
            }
        }
        for (nostr in listOf(false, true)) for (private in listOf(false, true)) {
            compose.runOnIdle {
                context = ConversationUiContext("$nostr-$private", if (private) "synthetic-other" else null, nostr)
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Image").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Image").performClick()
            compose.onNodeWithContentDescription("Image 1 of 1").assertExists()
            compose.onNodeWithContentDescription("Close").performClick()
        }
        compose.runOnIdle { ChatUiModeManager.set(app, ChatUiMode.Matrix) }
        compose.onNodeWithContentDescription("Image").performTouchInput { longClick() }
        compose.onNodeWithText("Image actions").assertExists()
        compose.onNodeWithText("Close actions").performClick()
        compose.runOnIdle { ChatUiModeManager.set(app, ChatUiMode.Bubbles) }
    }
}
