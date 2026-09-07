package com.bitchat.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.theme.BitchatTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Identical synthetic rows can also be run against the base revision for visual comparison. */
class ChatMediaRenderingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finalizedVoiceAndAttachmentsRenderAcrossChatModes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(app.cacheDir, "chat-ui-fixture").apply { mkdirs() }
        val before = File(fixture, "synthetic-live.aac").apply { writeBytes(byteArrayOf()) }
        val finished = File(fixture, "synthetic-finished.wav").apply { writeBytes(syntheticWave()) }
        val sent = File(fixture, "synthetic-sent.wav").apply { writeBytes(syntheticWave()) }
        val photo = File(fixture, "synthetic-image.png")
        val bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(30, 80, 110))
            drawCircle(320f, 80f, 50f, Paint().apply { color = android.graphics.Color.rgb(240, 180, 70) })
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val attachment = File(fixture, "synthetic-note.txt").apply { writeText("Synthetic attachment") }
        var path by mutableStateOf(before.path)
        var privateChat by mutableStateOf(true)
        var bubbles by mutableStateOf(true)
        var dark by mutableStateOf(true)
        val baseline = InstrumentationRegistry.getArguments().getString("baseline") == "true"
        compose.setContent {
            BitchatTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize().testTag("capture")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (privateChat) "Private chat · synthetic fixture" else "Public mesh · synthetic fixture")
                        val messages = listOf(
                            fixtureMessage("received-voice", "alice", "synthetic-other", path, BitchatMessageType.Audio, privateChat),
                            fixtureMessage("sent-voice", "previous-name", "synthetic-self", sent.path, BitchatMessageType.Audio, privateChat),
                            fixtureMessage("image", "alice", "synthetic-other", photo.path, BitchatMessageType.Image, privateChat),
                            fixtureMessage("file", "me", "synthetic-self", attachment.path, BitchatMessageType.File, privateChat),
                        )
                        messages.forEach { message ->
                            key(message.id) {
                                MessageItem(message, "me", syntheticMesh(), messages = messages, bubbles = bubbles)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { path = finished.path }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("00:01").fetchSemanticsNodes().size == 2 }
        if (!baseline) {
            compose.waitUntil(15_000) { compose.onAllNodes(hasStateDescription("Audio waveform")).fetchSemanticsNodes().size == 2 }
        }
        val evidence = File(app.filesDir, "chat-ui-evidence").apply { mkdirs() }
        File(evidence, "${if (baseline) "before" else "after"}-voice-replacement.png").outputStream().use {
            assertTrue(compose.onNodeWithTag("capture").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        for (private in listOf(false, true)) for (bubble in listOf(false, true)) for (night in listOf(false, true)) {
            compose.runOnIdle { privateChat = private; bubbles = bubble; dark = night }
            compose.waitForIdle()
            val name = "${if (baseline) "before" else "after"}-${if (private) "private" else "public"}-${if (bubble) "bubbles" else "matrix"}-${if (night) "dark" else "light"}.png"
            File(evidence, name).outputStream().use {
                assertTrue(compose.onNodeWithTag("capture").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}

private fun fixtureMessage(id: String, sender: String, peer: String, content: String, type: BitchatMessageType, privateChat: Boolean) =
    BitchatMessage(id = id, sender = sender, senderPeerID = peer, content = content, type = type,
        timestamp = Date(0), isPrivate = privateChat,
        deliveryStatus = if (peer == "synthetic-self") DeliveryStatus.Sent else null)

internal fun syntheticWave(): ByteArray {
    val rate = 16_000
    val bytes = rate * 2
    return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(bytes)
        repeat(rate) { index ->
            val envelope = 0.2 + 0.8 * (0.5 + 0.5 * sin(index * 12.0 / rate))
            putShort((sin(index * 2.0 * Math.PI * 440.0 / rate) * envelope * 20_000).toInt().toShort())
        }
    }.array()
}
