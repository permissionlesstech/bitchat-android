package com.bitchat.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.media.VoiceNotePlayer
import com.bitchat.android.ui.theme.BitchatTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatVisualStabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deliveryChangesKeepMessageTimestampAndMediaBoundsStable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File(app.cacheDir, "synthetic-stability.wav").apply { writeBytes(syntheticWave()) }
        val image = File(app.cacheDir, "synthetic-stability.png")
        Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).also { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val file = File(app.cacheDir, "synthetic-stability.txt").apply { writeText("Synthetic attachment") }
        val contents = mapOf(BitchatMessageType.Message to "Synthetic note.", BitchatMessageType.Audio to audio.path,
            BitchatMessageType.Image to image.path, BitchatMessageType.File to file.path)
        var textBody by mutableStateOf("Synthetic note.")
        var cancelled = 0
        var type by mutableStateOf(BitchatMessageType.Message)
        var status by mutableStateOf<DeliveryStatus?>(null)
        var bubbles by mutableStateOf(true)
        var width by mutableStateOf(320)
        var self by mutableStateOf(true)
        val timestamp = SimpleDateFormat(CHAT_TIMESTAMP_PATTERN, Locale.getDefault()).format(Date(0))
        compose.setContent {
            BitchatTheme(darkTheme = true) {
                Surface(Modifier.width(width.dp).fillMaxHeight().testTag("capture")) {
                    Column(Modifier.padding(16.dp)) {
                        MessageItem(
                            BitchatMessage(id = "synthetic-stability", sender = if (self) "me" else "alice",
                                senderPeerID = if (self) "synthetic-self" else "synthetic-other", content = if (type == BitchatMessageType.Message) textBody else contents.getValue(type),
                                type = type, timestamp = Date(0), isPrivate = true, deliveryStatus = status),
                            "me", syntheticMesh(), bubbles = bubbles, onCancelTransfer = { cancelled++ }, modifier = Modifier.testTag("row"),
                        )
                        Text("Next message", Modifier.testTag("next"))
                    }
                }
            }
        }
        val statuses = listOf(null, DeliveryStatus.Sending, DeliveryStatus.PartiallyDelivered(1, 3), DeliveryStatus.Sent,
            DeliveryStatus.Delivered("synthetic-peer", Date(0)), DeliveryStatus.Read("synthetic-peer", Date(0)), DeliveryStatus.Failed("synthetic"))
        val differences = mutableListOf<String>()
        val evidence = File(app.filesDir, "chat-ui-stability").apply { mkdirs() }
        val record = InstrumentationRegistry.getArguments().getString("recordRegression") == "true"
        val cases = contents.keys.map { it to "Synthetic note." } + listOf(
            BitchatMessageType.Message to "Synthetic text near a line boundary",
            BitchatMessageType.Message to "A longer synthetic message that wraps across several lines to exercise timestamp placement.",
        )
        for (logicalWidth in listOf(320, 411)) for (bubble in listOf(true, false)) for (own in listOf(true, false)) for ((kind, body) in cases) {
            compose.runOnIdle { width = logicalWidth; bubbles = bubble; self = own; type = kind; textBody = body; status = null }
            if (kind == BitchatMessageType.Audio) compose.waitUntil(10_000) { compose.onAllNodes(hasStateDescription("Audio waveform")).fetchSemanticsNodes().size == 1 }
            if (kind == BitchatMessageType.Image) compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Image").fetchSemanticsNodes().size == 1 }
            if (kind == BitchatMessageType.File) compose.waitUntil(10_000) { compose.onAllNodesWithText(file.name).fetchSemanticsNodes().size == 1 }
            fun bounds(): Map<String, String> {
                val result = mutableMapOf("row" to compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.toString(),
                    "next" to compose.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot.toString(),
                    "timestamp" to compose.onNode(hasText(timestamp, substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.toString())
                val media = when (kind) {
                    BitchatMessageType.Audio -> hasStateDescription("Audio waveform")
                    BitchatMessageType.Image -> hasContentDescription("Image")
                    BitchatMessageType.File -> hasText(file.name)
                    else -> hasText(body, substring = true)
                }
                result["content"] = compose.onNode(media, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.toString()
                return result
            }
            val initial = bounds()
            for ((index, nextStatus) in statuses.withIndex()) {
                // Image/file transfer animations intentionally replace their content; receipt ticks do not.
                if (nextStatus is DeliveryStatus.PartiallyDelivered && kind != BitchatMessageType.Audio) continue
                compose.runOnIdle { status = nextStatus }
                compose.waitForIdle()
                if (own && kind == BitchatMessageType.Audio && nextStatus is DeliveryStatus.PartiallyDelivered) {
                    val previous = cancelled
                    compose.onNodeWithContentDescription("Cancel").performClick()
                    compose.runOnIdle { assertTrue("Cancel callback must remain wired", cancelled == previous + 1) }
                }
                val current = bounds()
                val label = "$logicalWidth-${if (bubble) "bubbles" else "matrix"}-${if (own) "sent" else "received"}-$kind-$index"
                initial.forEach { (node, rect) -> if (current[node] != rect) differences += "$label bodyLength=${body.length} $node: $rect -> ${current[node]}" }
                if (logicalWidth == 320 && own && kind == BitchatMessageType.Audio && index in listOf(0, 2, 3, 5)) {
                    File(evidence, "${if (record) "before" else "after"}-$label.png").outputStream().use {
                        compose.onNodeWithTag("capture").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
        File(evidence, "${if (record) "before" else "after"}-bounds.txt").writeText(differences.joinToString("\n"))
        assertTrue("Delivery status displaced content:\n${differences.take(20).joinToString("\n")}", record || differences.isEmpty())
    }

    @Test fun playerKeepsGreenPlaybackAndBlueTransferColors() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File(app.cacheDir, "synthetic-color.wav").apply { writeBytes(syntheticWave()) }
        VoiceWaveformCache.put(audio.path, FloatArray(120) { 0.8f })
        var transfer by mutableStateOf<Float?>(null)
        compose.setContent {
            BitchatTheme(darkTheme = true) {
                Surface(color = Color.Black) { VoiceNotePlayer(audio.path, Modifier.width(300.dp), progressOverride = transfer) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("00:01").fetchSemanticsNodes().size == 1 }
        val waveform = compose.onNode(hasStateDescription("Audio waveform"))
        fun hasColor(red: Float, green: Float, blue: Float): Boolean {
            val pixels = waveform.captureToImage().toPixelMap()
            return (0 until pixels.width).any { x -> (0 until pixels.height).any { y ->
                val p = pixels[x, y]
                kotlin.math.abs(p.red - red) < 0.025f && kotlin.math.abs(p.green - green) < 0.025f && kotlin.math.abs(p.blue - blue) < 0.025f
            } }
        }
        assertTrue("Unplayed waveform must retain translucent green", hasColor(0f, 34f / 255f, 17f / 255f))
        waveform.performTouchInput { click(center) }
        assertTrue("Playback progress must remain green", hasColor(0f, 200f / 255f, 81f / 255f))
        compose.runOnIdle { transfer = 0.5f }
        assertTrue("Transfer progress must remain blue", hasColor(30f / 255f, 136f / 255f, 229f / 255f))
    }
}
