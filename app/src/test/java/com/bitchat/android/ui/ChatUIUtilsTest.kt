package com.bitchat.android.ui

import androidx.compose.ui.text.SpanStyle
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.theme.DarkBitchatPalette
import com.bitchat.android.ui.theme.LightBitchatPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUIUtilsTest {
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val palette = DarkBitchatPalette

    private fun message(
        content: String,
        sender: String = "alice",
        powDifficulty: Int? = null,
    ) = BitchatMessage(
        sender = sender,
        content = content,
        timestamp = Date(0),
        powDifficulty = powDifficulty,
    )

    // MARK: - Timestamp metadata

    @Test
    fun `text message metadata separates PoW badge with one space`() {
        assertEquals(
            "00:00:00 ⛨12b",
            formatTextMessageMetadata(message("hello", powDifficulty = 12), timeFormatter).text,
        )
    }

    @Test
    fun `text message metadata omits non-positive PoW difficulty`() {
        assertEquals(
            "00:00:00",
            formatTextMessageMetadata(message("hello", powDifficulty = 0), timeFormatter).text,
        )
    }

    // MARK: - Body with inline trailing timestamp

    @Test
    fun `body appends timestamp inline after the message text`() {
        val body = formatTextMessageBody(
            message = message("hello there"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
        )

        assertEquals("hello there  00:00:00", body.text)
    }

    @Test
    fun `body appends PoW badge after the inline timestamp`() {
        val body = formatTextMessageBody(
            message = message("mined", powDifficulty = 8),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
        )

        assertEquals("mined  00:00:00 ⛨8b", body.text)
    }

    @Test
    fun `body can omit the timestamp for callers that render it separately`() {
        val body = formatTextMessageBody(
            message = message("hello there"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        assertEquals("hello there", body.text)
    }

    @Test
    fun `body renders plain text in the neutral palette color, not terminal green`() {
        val body = formatTextMessageBody(
            message = message("plain words"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        val textStyle = body.spanStyles.first { it.start == 0 }
        assertEquals(palette.textPrimary, textStyle.item.color)
    }

    @Test
    fun `body does not bold plain text for the sender's own messages`() {
        // Regression guard: the old renderer bolded the entire body when the message was yours,
        // and again when you were mentioned, which is what made busy channels unreadable.
        val body = formatTextMessageBody(
            message = message("my own words", sender = "bob"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        assertTrue(
            "no span should force a bold weight",
            body.spanStyles.none { it.item.fontWeight?.weight?.let { w -> w >= 700 } == true }
        )
    }

    // MARK: - Mention chips

    private fun mentionChipSpans(body: androidx.compose.ui.text.AnnotatedString) =
        body.spanStyles.filter { it.item.background.isSpecified() }

    private fun androidx.compose.ui.graphics.Color.isSpecified() =
        this != androidx.compose.ui.graphics.Color.Unspecified && alpha > 0f

    @Test
    fun `mention renders as a single contiguous background chip`() {
        val body = formatTextMessageBody(
            message = message("hey @carol#04af what's up"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        val chips = mentionChipSpans(body)
        assertEquals("expected exactly one chip", 1, chips.size)

        // The chip must cover "@carol#04af" as one run so it paints without seams.
        val chip = chips.single()
        assertEquals("@carol#04af", body.text.substring(chip.start, chip.end))
    }

    @Test
    fun `mention targeting the current user uses the orange accent`() {
        val body = formatTextMessageBody(
            message = message("ping @bob now"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        val chip = mentionChipSpans(body).single()
        assertEquals(palette.accentOrange.copy(alpha = MENTION_CHIP_ALPHA_SELF), chip.item.background)

        val nameStyle: SpanStyle = body.spanStyles
            .first { it.start == chip.start && it.item.color == palette.accentOrange }
            .item
        assertEquals(palette.accentOrange, nameStyle.color)
    }

    @Test
    fun `mention of another user is tinted by that user's own peer color`() {
        val body = formatTextMessageBody(
            message = message("cc @carol#04af"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        val expected = colorForPeerSeed("carol#04af", palette.isDark)
        val chip = mentionChipSpans(body).single()
        assertEquals(expected.copy(alpha = MENTION_CHIP_ALPHA), chip.item.background)
    }

    @Test
    fun `message without mentions has no background chips`() {
        val body = formatTextMessageBody(
            message = message("no mentions in here"),
            currentUserNickname = "bob",
            palette = palette,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        assertTrue(mentionChipSpans(body).isEmpty())
    }

    // MARK: - System / action messages

    @Test
    fun `system message uses a double-slash prefix and no brackets`() {
        val text = formatSystemMessage(
            message = message("Tor started. Routing all chats via Tor", sender = "system"),
            palette = palette,
            timeFormatter = timeFormatter,
        ).text

        assertEquals("// Tor started. Routing all chats via Tor  00:00:00", text)
    }

    @Test
    fun `system message is not italic`() {
        // The old treatment was `* italic asterisks *`, which competed visually with real
        // messages despite being lower-priority narration.
        val annotated = formatSystemMessage(
            message = message("tor restarting", sender = "system"),
            palette = palette,
            timeFormatter = timeFormatter,
        )

        assertTrue(annotated.spanStyles.all { it.item.fontStyle == null })
    }

    // MARK: - Sender label

    @Test
    fun `sender label drops angle brackets and dims the hash suffix`() {
        val sender = formatTextMessageSender(
            message = message("hi", sender = "carol#04af"),
            currentUserNickname = "bob",
            meshService = FakeMeshServiceIds.stub(),
            palette = palette,
        )

        assertEquals("@carol#04af", sender.text)

        val suffixSpan = sender.spanStyles.first { sender.text.substring(it.start, it.end) == "#04af" }
        val nameSpan = sender.spanStyles.first { sender.text.substring(it.start, it.end) == "@carol" }
        assertNotNull(suffixSpan.item.color)
        assertTrue(
            "suffix must be dimmer than the name",
            suffixSpan.item.color.alpha < nameSpan.item.color.alpha
        )
    }

    @Test
    fun `sender label annotates the nickname for others but not for yourself`() {
        val other = formatTextMessageSender(
            message = message("hi", sender = "carol#04af"),
            currentUserNickname = "bob",
            meshService = FakeMeshServiceIds.stub(),
            palette = palette,
        )
        assertEquals(1, other.getStringAnnotations("nickname_click", 0, other.length).size)

        val mine = formatTextMessageSender(
            message = message("hi", sender = "bob"),
            currentUserNickname = "bob",
            meshService = FakeMeshServiceIds.stub(),
            palette = palette,
        )
        assertTrue(mine.getStringAnnotations("nickname_click", 0, mine.length).isEmpty())
    }

    // MARK: - Peer colors

    @Test
    fun `peer color hue is stable across light and dark, only chroma differs`() {
        // Hue derivation must stay byte-identical to iOS; only saturation/value are tuned for
        // the redesigned neutral message body.
        val dark = colorForPeerSeed("noise:abc", isDark = true)
        val light = colorForPeerSeed("noise:abc", isDark = false)

        val darkHsv = FloatArray(3)
        val lightHsv = FloatArray(3)
        rgbToHsv(dark.red, dark.green, dark.blue, darkHsv)
        rgbToHsv(light.red, light.green, light.blue, lightHsv)

        assertEquals(darkHsv[0].toDouble(), lightHsv[0].toDouble(), 1.0)
    }

    @Test
    fun `peer color avoids the orange hue reserved for self`() {
        // Sweep a range of seeds; none may land within the reserved orange band.
        repeat(500) { i ->
            val color = colorForPeerSeed("noise:seed$i", isDark = true)
            val hsv = FloatArray(3)
            rgbToHsv(color.red, color.green, color.blue, hsv)
            val distanceFromOrange = kotlin.math.abs(hsv[0] - 30f)
            assertTrue(
                "seed$i resolved to ${hsv[0]}°, inside the reserved orange band",
                distanceFromOrange >= 17f || hsv[1] < 0.01f
            )
        }
    }

    @Test
    fun `light palette is not the dark palette`() {
        assertNull(null)
        assertTrue(LightBitchatPalette.textPrimary != DarkBitchatPalette.textPrimary)
        assertTrue(LightBitchatPalette.isDark != DarkBitchatPalette.isDark)
    }

    private fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        out[0] = when {
            delta == 0f -> 0f
            max == r -> (60f * (((g - b) / delta) % 6f) + 360f) % 360f
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        out[1] = if (max == 0f) 0f else delta / max
        out[2] = max
    }
}
