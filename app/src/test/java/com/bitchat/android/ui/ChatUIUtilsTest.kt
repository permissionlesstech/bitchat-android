package com.bitchat.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.ChatVisualTokens
import com.bitchat.android.ui.theme.DarkBitchatColorScheme
import com.bitchat.android.ui.theme.DarkBitchatPalette
import com.bitchat.android.ui.theme.LightBitchatColorScheme
import com.bitchat.android.ui.theme.LightBitchatPalette
import com.bitchat.android.ui.theme.MessageBodyTextStyle
import com.bitchat.android.ui.theme.MessageSenderTextStyle
import com.bitchat.android.ui.theme.PeerColorStyle
import com.bitchat.android.ui.theme.colorForPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs under Robolectric because URL detection in message bodies goes through
 * `android.util.Patterns.WEB_URL`, which is null on a bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
class ChatUIUtilsTest {
    private val timeFormatter = SimpleDateFormat(CHAT_TIMESTAMP_PATTERN, Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val palette = DarkBitchatPalette
    private val colorScheme = DarkBitchatColorScheme

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
            "00:00 ⛨12b",
            formatTextMessageMetadata(message("hello", powDifficulty = 12), timeFormatter).text,
        )
    }

    @Test
    fun `text message metadata omits non-positive PoW difficulty`() {
        assertEquals(
            "00:00",
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
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
            timeFormatter = timeFormatter,
        )

        assertEquals("hello there  00:00", body.text)
        val timestamp = body.spanStyles.first {
            body.text.substring(it.start, it.end) == "  00:00"
        }.item
        assertEquals(10.sp, timestamp.fontSize)
        assertEquals(FontWeight.Normal, timestamp.fontWeight)
        assertEquals(palette.textTertiary, timestamp.color)
    }

    @Test
    fun `body appends PoW badge after the inline timestamp`() {
        val body = formatTextMessageBody(
            message = message("mined", powDifficulty = 8),
            currentUserNickname = "bob",
            palette = palette,
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
            timeFormatter = timeFormatter,
        )

        assertEquals("mined  00:00 ⛨8b", body.text)
        val timestampAndPow = body.spanStyles.first {
            body.text.substring(it.start, it.end) == "  00:00 ⛨8b"
        }.item
        assertEquals(10.sp, timestampAndPow.fontSize)
        assertEquals(palette.textTertiary, timestampAndPow.color)
    }

    @Test
    fun `body can omit the timestamp for callers that render it separately`() {
        val body = formatTextMessageBody(
            message = message("hello there"),
            currentUserNickname = "bob",
            palette = palette,
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
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
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        val textStyle = body.spanStyles.first { it.start == 0 }
        assertEquals(colorScheme.onSurface, textStyle.item.color)
    }

    @Test
    fun `chat text styles match the exported type scale`() {
        assertEquals(14.sp, MessageBodyTextStyle.fontSize)
        assertEquals(20.sp, MessageBodyTextStyle.lineHeight)
        assertEquals(FontWeight.Normal, MessageBodyTextStyle.fontWeight)
        assertEquals(BitchatFontFamily, MessageBodyTextStyle.fontFamily)
        assertEquals(14.sp, MessageSenderTextStyle.fontSize)
        assertEquals(16.sp, MessageSenderTextStyle.lineHeight)
        assertEquals(FontWeight.SemiBold, MessageSenderTextStyle.fontWeight)
    }

    @Test
    fun `body does not bold plain text for the sender's own messages`() {
        // Regression guard: the old renderer bolded the entire body when the message was yours,
        // and again when you were mentioned, which is what made busy channels unreadable.
        val body = formatTextMessageBody(
            message = message("my own words", sender = "bob"),
            currentUserNickname = "bob",
            palette = palette,
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
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
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
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
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        val chip = mentionChipSpans(body).single()
        assertEquals(palette.accentOrange.copy(alpha = MENTION_CHIP_ALPHA_SELF), chip.item.background)
        assertEquals(0.2f, chip.item.background.alpha)

        val nameStyle: SpanStyle = body.spanStyles
            .first { it.start == chip.start && it.item.color == palette.accentOrange }
            .item
        assertEquals(palette.accentOrange, nameStyle.color)
    }

    @Test
    fun `mention of another user is tinted by that user's own peer color`() {
        val pubkey = "0123456789abcdef".repeat(4)
        val identity = PeerIdentity.nostr(pubkey)
        val mentionPeerIdentities = buildMentionPeerIdentityMap(
            messages = listOf(
                BitchatMessage(
                    sender = "carol#04af",
                    content = "hello",
                    timestamp = Date(0),
                    senderPeerID = "nostr:${pubkey.take(8)}",
                    senderNostrPubkey = pubkey,
                )
            )
        )
        val body = formatTextMessageBody(
            message = message("cc @carol#04af"),
            currentUserNickname = "bob",
            palette = palette,
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
            mentionPeerIdentities = mentionPeerIdentities,
            timeFormatter = timeFormatter,
            includeTimestamp = false,
        )

        val expected = colorForPeer(identity, palette)
        val chip = mentionChipSpans(body).single()
        assertEquals(expected.copy(alpha = MENTION_CHIP_ALPHA), chip.item.background)
        assertTrue(body.spanStyles.any { it.item.color == expected })
    }

    @Test
    fun `composer colors nickname and hash suffix from the mentioned peer identity`() {
        val pubkey = "0123456789abcdef".repeat(4)
        val identity = PeerIdentity.nostr(pubkey)
        val token = "@carol#04af"
        val input = "ping $token now"
        val transformed = MentionVisualTransformation(
            mentionPeerIdentities = mapOf("carol#04af" to identity),
            palette = palette,
        ).filter(AnnotatedString(input)).text

        val expectedColor = colorForPeer(identity, palette)
        val tokenStart = input.indexOf(token)
        val suffixStart = input.indexOf("#04af")
        val tokenEnd = tokenStart + token.length

        assertEquals(input, transformed.text)
        assertTrue(transformed.spanStyles.any {
            it.start == tokenStart &&
                it.end == tokenEnd &&
                it.item.background == expectedColor.copy(alpha = MENTION_CHIP_ALPHA)
        })
        assertTrue(transformed.spanStyles.any {
            it.start == tokenStart &&
                it.end == suffixStart &&
                it.item.color == expectedColor
        })
        assertTrue(transformed.spanStyles.any {
            it.start == suffixStart &&
                it.end == tokenEnd &&
                it.item.color == expectedColor.copy(alpha = SUFFIX_ALPHA)
        })
    }

    @Test
    fun `ambiguous base nickname is not assigned to the wrong peer`() {
        val firstIdentity = PeerIdentity.nostr("11111111".repeat(8))
        val secondIdentity = PeerIdentity.nostr("22222222".repeat(8))
        val identities = buildMentionPeerIdentityMap(
            messages = emptyList(),
            knownPeers = listOf(
                "alice#1111" to firstIdentity,
                "alice#2222" to secondIdentity,
            )
        )

        assertEquals(firstIdentity, identities["alice#1111"])
        assertEquals(secondIdentity, identities["alice#2222"])
        assertFalse(identities.containsKey("alice"))
        assertEquals(
            firstIdentity,
            resolveMentionPeerIdentity("@alice#1111", identities)
        )
        assertEquals(null, resolveMentionPeerIdentity("@alice", identities))
    }

    @Test
    fun `message without mentions has no background chips`() {
        val body = formatTextMessageBody(
            message = message("no mentions in here"),
            currentUserNickname = "bob",
            palette = palette,
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
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
            contentColor = colorScheme.onSurface,
            timeFormatter = timeFormatter,
        ).text

        assertEquals("// Tor started. Routing all chats via Tor  00:00", text)
    }

    @Test
    fun `system message is not italic`() {
        // The old treatment was `* italic asterisks *`, which competed visually with real
        // messages despite being lower-priority narration.
        val annotated = formatSystemMessage(
            message = message("tor restarting", sender = "system"),
            contentColor = colorScheme.onSurface,
            timeFormatter = timeFormatter,
        )

        assertTrue(annotated.spanStyles.all { it.item.fontStyle == null })
    }

    @Test
    fun `system action and timestamp use their exported weights sizes and opacity`() {
        val annotated = formatSystemMessage(
            message = message("tor restarting", sender = "system"),
            contentColor = colorScheme.onSurface,
            timeFormatter = timeFormatter,
        )

        val action = annotated.spanStyles.first {
            annotated.text.substring(it.start, it.end) == "// tor restarting"
        }.item
        val time = annotated.spanStyles.first {
            annotated.text.substring(it.start, it.end) == "  00:00"
        }.item

        assertEquals(12.sp, action.fontSize)
        assertEquals(FontWeight.Medium, action.fontWeight)
        assertEquals(colorScheme.onSurface.copy(alpha = 0.5f), action.color)
        assertEquals(10.sp, time.fontSize)
        assertEquals(FontWeight.Normal, time.fontWeight)
        assertEquals(colorScheme.onSurface.copy(alpha = 0.5f), time.color)
    }

    // MARK: - Sender label

    @Test
    fun `sender label drops angle brackets and dims the hash suffix`() {
        val sender = formatTextMessageSender(
            message = message("hi", sender = "carol#04af"),
            currentUserNickname = "bob",
            myPeerID = "peer-me",
            palette = palette,
        )

        assertEquals("@carol #04af", sender.text)

        val suffixSpan = sender.spanStyles.first { sender.text.substring(it.start, it.end) == "#04af" }
        val nameSpan = sender.spanStyles.first { sender.text.substring(it.start, it.end) == "@carol" }
        assertNotNull(suffixSpan.item.color)
        assertEquals(14.sp, nameSpan.item.fontSize)
        assertEquals(FontWeight.SemiBold, nameSpan.item.fontWeight)
        assertEquals(14.sp, suffixSpan.item.fontSize)
        assertEquals(FontWeight.Normal, suffixSpan.item.fontWeight)
        assertEquals(ChatVisualTokens.SenderSuffixAlpha, suffixSpan.item.color.alpha)
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
            myPeerID = "peer-me",
            palette = palette,
        )
        assertEquals(1, other.getStringAnnotations("nickname_click", 0, other.length).size)

        val mine = formatTextMessageSender(
            message = message("hi", sender = "bob"),
            currentUserNickname = "bob",
            myPeerID = "peer-me",
            palette = palette,
        )
        assertTrue(mine.getStringAnnotations("nickname_click", 0, mine.length).isEmpty())
    }

    // MARK: - Peer colors

    @Test
    fun `peer identity factories normalize stable IDs without resolving UI colors`() {
        assertEquals(
            PeerIdentity.mesh("abcdef"),
            PeerIdentity.mesh("ABCDEF")
        )
        assertEquals(
            PeerIdentity.nostr("abcdef"),
            PeerIdentity.nostr("ABCDEF")
        )
        assertEquals(
            PeerIdentity.nostr("abcdef"),
            PeerIdentity.nostr("nostr:nostr_ABCDEF")
        )
        assertEquals(
            "nostr:nostr:abcdef01",
            PeerIdentity.nostr("ABCDEF0123456789").stableKey
        )
        assertEquals("alice#1234", PeerIdentity.nickname("ALICE#1234").stableKey)
    }

    @Test
    fun `peer color hue is stable across light and dark, only chroma differs`() {
        // Hue derivation must stay byte-identical to iOS; only saturation/value are tuned per
        // theme so dark mode stays muted-but-bright and light mode stays deep-but-readable.
        val identity = PeerIdentity.mesh("abc")
        val dark = colorForPeer(identity, DarkBitchatPalette)
        val light = colorForPeer(identity, LightBitchatPalette)

        val darkHsv = FloatArray(3)
        val lightHsv = FloatArray(3)
        rgbToHsv(dark.red, dark.green, dark.blue, darkHsv)
        rgbToHsv(light.red, light.green, light.blue, lightHsv)

        assertEquals(darkHsv[0].toDouble(), lightHsv[0].toDouble(), 1.0)
        assertEquals(PeerColorStyle.Dark.saturation.toDouble(), darkHsv[1].toDouble(), 0.01)
        assertEquals(PeerColorStyle.Dark.value.toDouble(), darkHsv[2].toDouble(), 0.01)
        assertEquals(PeerColorStyle.Light.saturation.toDouble(), lightHsv[1].toDouble(), 0.01)
        assertEquals(PeerColorStyle.Light.value.toDouble(), lightHsv[2].toDouble(), 0.01)
        // Dark theme: muted chroma, never dark (readable on near-black).
        assertTrue(darkHsv[1] < 0.75f)
        assertTrue(darkHsv[2] >= 0.75f)
        // Light theme: avoid neon / near-white peer labels.
        assertTrue(lightHsv[1] < 0.85f)
        assertTrue(lightHsv[2] <= 0.55f)
    }

    @Test
    fun `peer color avoids the orange hue reserved for self`() {
        // Sweep a range of seeds; none may land within the reserved orange band.
        repeat(500) { i ->
            val color = colorForPeer(
                PeerIdentity.mesh("seed$i"),
                DarkBitchatPalette
            )
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
    fun `material owns standard text while Bitchat palette owns peer chroma`() {
        assertEquals(Color(0xFFF5F5F5), DarkBitchatColorScheme.onSurface)
        assertTrue(LightBitchatColorScheme.onSurface != DarkBitchatColorScheme.onSurface)
        assertTrue(
            LightBitchatPalette.peerColors != DarkBitchatPalette.peerColors
        )
    }

    @Test
    fun `geohash chat and people sheet resolve the same full Nostr identity`() {
        val pubkey = "ABCDEF0123456789".repeat(4)
        val peopleIdentity = PeerIdentity.nostr(pubkey)
        val chatIdentity = peerIdentityForMessage(
            BitchatMessage(
                sender = "alice#1234",
                content = "hello",
                timestamp = Date(0),
                senderPeerID = "nostr:${pubkey.take(8)}",
                senderNostrPubkey = pubkey,
            )
        )

        assertEquals(peopleIdentity, chatIdentity)
        assertEquals(
            colorForPeer(peopleIdentity, palette),
            colorForPeer(chatIdentity, palette)
        )
    }

    @Test
    fun `mesh chat and people sheet resolve the same peer identity`() {
        val peerID = "ABCDEF0123456789"
        val peopleIdentity = PeerIdentity.mesh(peerID)
        val chatIdentity = peerIdentityForMessage(
            BitchatMessage(
                sender = "alice#1234",
                content = "hello",
                timestamp = Date(0),
                senderPeerID = peerID,
            )
        )

        assertEquals(peopleIdentity, chatIdentity)
    }

    @Test
    fun `full Nostr identity wins over a truncated routing alias`() {
        val pubkey = "0123456789ABCDEF".repeat(4)
        val identity = peerIdentityForMessage(
            BitchatMessage(
                sender = "alice",
                content = "hello",
                timestamp = Date(0),
                senderPeerID = "nostr_${pubkey.take(16)}",
                senderNostrPubkey = pubkey,
            )
        )

        assertEquals(PeerIdentity.nostr(pubkey), identity)
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
