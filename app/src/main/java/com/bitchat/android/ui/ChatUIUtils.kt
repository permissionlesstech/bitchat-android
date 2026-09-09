package com.bitchat.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.BitchatPalette
import com.bitchat.android.ui.theme.ChatVisualTokens
import com.bitchat.android.ui.theme.colorForPeer
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility functions for ChatScreen UI components
 * Extracted from ChatScreen.kt for better organization
 */

/** Opacity applied to the `#abcd` disambiguation suffix so the readable name dominates. */
internal const val SUFFIX_ALPHA = ChatVisualTokens.SenderSuffixAlpha

/** Compact transcript timestamp; seconds add noise without helping conversation scanning. */
internal const val CHAT_TIMESTAMP_PATTERN = "HH:mm"

/** Background opacity for a mention chip referring to somebody else. */
internal const val MENTION_CHIP_ALPHA = ChatVisualTokens.HighlightAlpha

/** Background opacity for a mention chip referring to you. Slightly stronger to catch the eye. */
internal const val MENTION_CHIP_ALPHA_SELF = ChatVisualTokens.HighlightAlpha

/**
 * Mention token grammar shared by rendered messages and the composer.
 *
 * The optional `#abcd` suffix is part of the mention because it disambiguates peers that use the
 * same nickname. Keeping one regex prevents the composer from styling only `@name` while the
 * rendered transcript styles the complete token.
 */
internal val MENTION_TOKEN_REGEX = Regex("@([\\p{L}0-9_]+(?:#[a-fA-F0-9]{4})?)")

/**
 * Get RSSI-based color for signal strength visualization
 */
fun getRSSIColor(rssi: Int): Color {
    return when {
        rssi >= -50 -> Color(0xFF00FF00) // Bright green
        rssi >= -60 -> Color(0xFF80FF00) // Green-yellow
        rssi >= -70 -> Color(0xFFFFFF00) // Yellow
        rssi >= -80 -> Color(0xFFFF8000) // Orange
        else -> Color(0xFFFF4444) // Red
    }
}

/** Thin space (U+2009) separating a display name from its `#abcd` disambiguation suffix. */
internal const val SUFFIX_THIN_SPACE = " "

/**
 * Build the sender label shown above the first message of a group.
 *
 * Renders `@name` plus a dimmed `#abcd` suffix, separated by a thin space. The name carries
 * a `nickname_click` annotation for everyone except yourself.
 */
fun formatTextMessageSender(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    palette: BitchatPalette
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val isSelf = message.isFromSelf(currentUserNickname, myPeerID)
    val senderColor = if (isSelf) {
        palette.accentOrange
    } else {
        colorForPeer(peerIdentityForMessage(message), palette)
    }
    val senderWeight = FontWeight.SemiBold
    val (baseName, suffix) = splitSuffix(message.sender)

    builder.pushStyle(
        SpanStyle(
            color = senderColor,
            fontSize = ChatVisualTokens.SenderFontSize,
            fontWeight = senderWeight
        )
    )
    builder.append("@")
    val nicknameStart = builder.length
    builder.append(truncateNickname(baseName))
    val nicknameEnd = builder.length
    if (!isSelf) {
        builder.addStringAnnotation(
            tag = "nickname_click",
            annotation = message.originalSender ?: message.sender,
            start = nicknameStart,
            end = nicknameEnd
        )
    }
    builder.pop()

    if (suffix.isNotEmpty()) {
        builder.append(SUFFIX_THIN_SPACE)
        builder.pushStyle(
            SpanStyle(
                color = senderColor.copy(alpha = SUFFIX_ALPHA),
                fontSize = ChatVisualTokens.SenderFontSize,
                fontWeight = FontWeight.Normal
            )
        )
        builder.append(suffix)
        builder.pop()
    }

    return builder.toAnnotatedString()
}

/**
 * Build the compact timestamp and optional proof-of-work label.
 *
 * Used standalone by media rows; text messages get the same span appended inline to the end of
 * their body via [appendBodyTimestamp].
 */
fun formatTextMessageMetadata(
    message: BitchatMessage,
    timeFormatter: SimpleDateFormat = SimpleDateFormat(CHAT_TIMESTAMP_PATTERN, Locale.getDefault())
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    builder.pushStyle(
        SpanStyle(
            color = Color.Gray.copy(alpha = 0.7f),
            fontSize = (BASE_FONT_SIZE - 4).sp
        )
    )
    builder.append(timeFormatter.format(message.timestamp))
    message.powDifficulty?.takeIf { it > 0 }?.let { bits ->
        builder.append(" ⛨${bits}b")
    }
    builder.pop()
    return builder.toAnnotatedString()
}

/**
 * Append the timestamp (and optional PoW difficulty) directly after the message body so it
 * trails the final words rather than occupying its own column.
 *
 * Deliberately carries no click annotation: the timestamp is decoration, and making it
 * tappable would create dead zones inside the message body.
 */
private fun appendTimestampText(
    builder: AnnotatedString.Builder,
    message: BitchatMessage,
    timeFormatter: SimpleDateFormat
) {
    builder.append("  ")
    builder.append(timeFormatter.format(message.timestamp))
    message.powDifficulty?.takeIf { it > 0 }?.let { bits ->
        builder.append(" ⛨${bits}b")
    }
}

private fun appendBodyTimestamp(
    builder: AnnotatedString.Builder,
    message: BitchatMessage,
    palette: BitchatPalette,
    timeFormatter: SimpleDateFormat,
) {
    builder.pushStyle(
        SpanStyle(
            color = palette.textTertiary,
            fontSize = ChatVisualTokens.SystemTimeFontSize,
            fontWeight = FontWeight.Normal,
        )
    )
    appendTimestampText(builder, message, timeFormatter)
    builder.pop()
}

private fun appendMutedTimestamp(
    builder: AnnotatedString.Builder,
    message: BitchatMessage,
    contentColor: Color,
    timeFormatter: SimpleDateFormat,
) {
    builder.pushStyle(
        SpanStyle(
            color = contentColor.copy(alpha = ChatVisualTokens.MutedTextAlpha),
            fontSize = ChatVisualTokens.SystemTimeFontSize,
            fontWeight = FontWeight.Normal,
        )
    )
    appendTimestampText(builder, message, timeFormatter)
    builder.pop()
}

/**
 * Build the message body: neutral text with mention/URL/geohash accents, followed by an inline
 * trailing timestamp.
 *
 * Body text is intentionally neutral rather than peer-colored. Colour is reserved for
 * `@names`, which is what makes a busy channel scannable.
 */
fun formatTextMessageBody(
    message: BitchatMessage,
    currentUserNickname: String,
    palette: BitchatPalette,
    contentColor: Color,
    linkColor: Color,
    mentionPeerIdentities: Map<String, PeerIdentity> = emptyMap(),
    timeFormatter: SimpleDateFormat = SimpleDateFormat(CHAT_TIMESTAMP_PATTERN, Locale.getDefault()),
    includeTimestamp: Boolean = true
): AnnotatedString {
    val builder = AnnotatedString.Builder()

    appendIOSFormattedContent(
        builder = builder,
        content = message.content,
        currentUserNickname = currentUserNickname,
        palette = palette,
        contentColor = contentColor,
        linkColor = linkColor,
        mentionPeerIdentities = mentionPeerIdentities,
    )

    if (includeTimestamp) {
        appendBodyTimestamp(builder, message, palette, timeFormatter)
    }
    return builder.toAnnotatedString()
}

/**
 * Build a system / background-action line, e.g. `// Tor started. Routing all chats… 11:09:56`.
 *
 * The `//` prefix reads as machine narration in a monospace context and is far quieter than
 * the previous `* italic asterisk *` treatment, which competed with real messages.
 */
fun formatSystemMessage(
    message: BitchatMessage,
    contentColor: Color,
    timeFormatter: SimpleDateFormat = SimpleDateFormat(CHAT_TIMESTAMP_PATTERN, Locale.getDefault())
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    builder.pushStyle(
        SpanStyle(
            color = contentColor.copy(alpha = ChatVisualTokens.MutedTextAlpha),
            fontSize = ChatVisualTokens.SystemActionFontSize,
            fontWeight = FontWeight.Medium,
        )
    )
    builder.append("// ")
    builder.append(message.content)
    builder.pop()

    appendMutedTimestamp(builder, message, contentColor, timeFormatter)
    return builder.toAnnotatedString()
}

/**
 * Header line for media (image / audio / file) rows.
 *
 * Matches the text-message treatment: `@name#abcd` with no angle brackets, followed by an
 * inline trailing timestamp. Media rows have no body text to trail, so the timestamp sits on
 * the same line as the name.
 */
fun formatMessageHeaderAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    palette: BitchatPalette,
    contentColor: Color,
    timeFormatter: SimpleDateFormat = SimpleDateFormat(CHAT_TIMESTAMP_PATTERN, Locale.getDefault()),
    includeSender: Boolean = true
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val isSelf = message.isFromSelf(currentUserNickname, myPeerID)

    if (message.sender == "system") {
        return formatSystemMessage(message, contentColor, timeFormatter)
    }

    if (includeSender) {
        val baseColor = if (isSelf) {
            palette.accentOrange
        } else {
            colorForPeer(peerIdentityForMessage(message), palette)
        }
        val (baseName, suffix) = splitSuffix(message.sender)

        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = ChatVisualTokens.SenderFontSize,
                fontWeight = FontWeight.SemiBold,
            )
        )
        builder.append("@")
        val nicknameStart = builder.length
        builder.append(truncateNickname(baseName))
        val nicknameEnd = builder.length
        if (!isSelf) {
            builder.addStringAnnotation(
                tag = "nickname_click",
                annotation = (message.originalSender ?: message.sender),
                start = nicknameStart,
                end = nicknameEnd
            )
        }
        builder.pop()

        if (suffix.isNotEmpty()) {
            builder.append(SUFFIX_THIN_SPACE)
            builder.pushStyle(
                SpanStyle(
                    color = baseColor.copy(alpha = SUFFIX_ALPHA),
                    fontSize = ChatVisualTokens.SenderFontSize,
                    fontWeight = FontWeight.Normal,
                )
            )
            builder.append(suffix)
            builder.pop()
        }
    }

    appendMutedTimestamp(builder, message, contentColor, timeFormatter)
    return builder.toAnnotatedString()
}

/**
 * Split a name into base and a '#abcd' suffix if present (matches iOS splitSuffix exactly)
 */
fun splitSuffix(name: String): Pair<String, String> {
    if (name.length < 5) return Pair(name, "")
    
    val suffix = name.takeLast(5)
    if (suffix.startsWith("#") && suffix.drop(1).all { 
        it.isDigit() || it.lowercaseChar() in 'a'..'f' 
    }) {
        val base = name.dropLast(5)
        return Pair(base, suffix)
    }
    
    return Pair(name, "")
}

/**
 * Build a case-insensitive mention-token lookup from canonical peer identities.
 *
 * Suffixed names such as `alice#04af` resolve exactly. Their unsuffixed base is only retained when
 * it identifies one peer; ambiguous bases are deliberately omitted rather than coloring a mention
 * as the wrong person.
 */
internal fun buildMentionPeerIdentityMap(
    messages: List<BitchatMessage>,
    knownPeers: List<Pair<String, PeerIdentity>> = emptyList(),
): Map<String, PeerIdentity> {
    val candidates = linkedMapOf<String, MutableSet<PeerIdentity>>()

    fun add(displayName: String, identity: PeerIdentity) {
        val normalizedName = displayName.trim().removePrefix("@")
        if (normalizedName.isEmpty()) return

        val (baseName, suffix) = splitSuffix(normalizedName)
        val exactKey = normalizedName.lowercase(Locale.ROOT)
        candidates.getOrPut(exactKey) { linkedSetOf() }.add(identity)

        if (suffix.isNotEmpty()) {
            val baseKey = baseName.lowercase(Locale.ROOT)
            candidates.getOrPut(baseKey) { linkedSetOf() }.add(identity)
        }
    }

    messages
        .asSequence()
        .filterNot { it.sender == "system" }
        .forEach { add(it.sender, peerIdentityForMessage(it)) }
    knownPeers.forEach { (displayName, identity) -> add(displayName, identity) }

    return candidates.mapNotNull { (token, identities) ->
        identities.singleOrNull()?.let { token to it }
    }.toMap()
}

internal fun resolveMentionPeerIdentity(
    mention: String,
    mentionPeerIdentities: Map<String, PeerIdentity>,
): PeerIdentity? {
    val mentionWithoutAt = mention.trim().removePrefix("@")
    val baseName = splitSuffix(mentionWithoutAt).first
    return mentionPeerIdentities[mentionWithoutAt.lowercase(Locale.ROOT)]
        ?: mentionPeerIdentities[baseName.lowercase(Locale.ROOT)]
}

/**
 * Resolve the deterministic color for a mention on every surface that displays one.
 *
 * Exact suffixed tokens win; an unsuffixed nickname is only present in the identity map when it is
 * unambiguous. The nickname fallback preserves the legacy behavior for peers with no stable ID.
 */
internal fun colorForMention(
    mention: String,
    mentionPeerIdentities: Map<String, PeerIdentity>,
    palette: BitchatPalette,
): Color {
    val mentionWithoutAt = mention.trim().removePrefix("@")
    val identity = resolveMentionPeerIdentity(mentionWithoutAt, mentionPeerIdentities)
        ?: PeerIdentity.nickname(mentionWithoutAt)
    return colorForPeer(identity, palette)
}

/**
 * A bare `anon` label means the geohash heartbeat has not announced a username yet. The transport
 * may append a `#abcd` disambiguator, which does not turn it into an announced name. Names such as
 * `anon1234`, `anonymous`, and `anonracer` are real announced usernames.
 */
internal fun isUnannouncedNickname(displayName: String): Boolean {
    val base = splitSuffix(displayName.trim()).first
    return base.equals("anon", ignoreCase = true)
}

/**
 * iOS-style content formatting with proper hashtag and mention handling.
 *
 * Redesign notes:
 *  - Plain text renders in Material `onSurface`; colour is reserved for `@mentions`,
 *    links and geohashes.
 *  - Mentions get a tinted background chip so they read as a distinct token inside a sentence.
 *    The chip is tinted by *the mentioned peer's* colour, not the sender's, so `@alice` looks
 *    the same everywhere she is referenced.
 *  - Neither "self" nor "you were mentioned" bolds the whole body any more. Bolding entire
 *    paragraphs was the single biggest source of visual noise in the old layout; the mention
 *    chip carries that emphasis instead.
 */
private fun appendIOSFormattedContent(
    builder: AnnotatedString.Builder,
    content: String,
    currentUserNickname: String,
    palette: BitchatPalette,
    contentColor: Color,
    linkColor: Color,
    mentionPeerIdentities: Map<String, PeerIdentity>,
) {
    val hashtagPattern = "#([a-zA-Z0-9_]+)".toRegex()
    
    val hashtagMatches = hashtagPattern.findAll(content).toList()
    val mentionMatches = MENTION_TOKEN_REGEX.findAll(content).toList()
    
    // Combine and sort matches, but exclude hashtags that overlap with mentions
    val mentionRanges = mentionMatches.map { it.range }
    fun overlapsMention(range: IntRange): Boolean {
        return mentionRanges.any { mentionRange ->
            range.first < mentionRange.last && range.last > mentionRange.first
        }
    }
    
    val allMatches = mutableListOf<Pair<IntRange, String>>()
    
    // Add hashtag matches that don't overlap with mentions
    for (match in hashtagMatches) {
        if (!overlapsMention(match.range)) {
            allMatches.add(match.range to "hashtag")
        }
    }
    
    // Add all mention matches
    for (match in mentionMatches) {
        allMatches.add(match.range to "mention") 
    }

    // Add standalone geohash matches (e.g., "#9q") that are not part of another word
    // We use MessageSpecialParser to find exact ranges; then merge with existing ranges avoiding overlaps
    val geoMatches = MessageSpecialParser.findStandaloneGeohashes(content)
    for (gm in geoMatches) {
        val range = gm.start until gm.endExclusive
        if (!overlapsMention(range)) {
            allMatches.add(range to "geohash")
        }
    }

    // Add URL matches (http/https/www/bare domains). Exclude overlaps with mentions.
    val urlMatches = MessageSpecialParser.findUrls(content)
    for (um in urlMatches) {
        val range = um.start until um.endExclusive
        if (!overlapsMention(range)) {
            allMatches.add(range to "url")
        }
    }

    // Remove generic hashtag matches that overlap with detected geohash ranges to avoid duplicate rendering
    fun rangesOverlap(a: IntRange, b: IntRange): Boolean {
        return a.first < b.last && a.last > b.first
    }
    val urlRanges = allMatches.filter { it.second == "url" }.map { it.first }
    val geoRanges = allMatches.filter { it.second == "geohash" }.map { it.first }
    if (geoRanges.isNotEmpty() || urlRanges.isNotEmpty()) {
        val iterator = allMatches.listIterator()
        while (iterator.hasNext()) {
            val (range, type) = iterator.next()
            // Remove generic hashtags that overlap with geohashes or URLs, and geohashes that overlap with URLs
            val overlapsGeo = geoRanges.any { rangesOverlap(range, it) }
            val overlapsUrl = urlRanges.any { rangesOverlap(range, it) }
            if ((type == "hashtag" && (overlapsGeo || overlapsUrl)) || (type == "geohash" && overlapsUrl)) iterator.remove()
        }
    }
    
    allMatches.sortBy { it.first.first }

    val plainStyle = SpanStyle(
        color = contentColor,
        fontSize = BASE_FONT_SIZE.sp,
        fontWeight = FontWeight.Normal
    )
    val linkStyle = SpanStyle(
        color = linkColor,
        fontSize = BASE_FONT_SIZE.sp,
        fontWeight = FontWeight.Normal,
        textDecoration = TextDecoration.Underline
    )

    var lastEnd = 0

    for ((range, type) in allMatches) {
        // Add text before match
        if (lastEnd < range.first) {
            val beforeText = content.substring(lastEnd, range.first)
            if (beforeText.isNotEmpty()) {
                builder.pushStyle(plainStyle)
                builder.append(beforeText)
                builder.pop()
            }
        }
        
        // Add styled match
        val matchText = content.substring(range.first, range.last + 1)
        when (type) {
            "mention" -> {
                // iOS-style mention with hashtag suffix support
                val mentionWithoutAt = matchText.removePrefix("@")
                val (mBase, mSuffix) = splitSuffix(mentionWithoutAt)

                // Mentions targeting you are the one thing worth shouting about.
                val isMentionToMe = mBase == currentUserNickname
                val mentionColor = if (isMentionToMe) {
                    palette.accentOrange
                } else {
                    colorForMention(
                        mention = mentionWithoutAt,
                        mentionPeerIdentities = mentionPeerIdentities,
                        palette = palette,
                    )
                }
                val chipAlpha = if (isMentionToMe) MENTION_CHIP_ALPHA_SELF else MENTION_CHIP_ALPHA
                val mentionWeight = if (isMentionToMe) FontWeight.Bold else FontWeight.SemiBold

                // A single outer span carrying the background makes the chip render as one
                // continuous rectangle. Pushing the background per-token would leave hairline
                // seams between "@", the name and the "#abcd" suffix.
                builder.pushStyle(SpanStyle(background = mentionColor.copy(alpha = chipAlpha)))

                builder.pushStyle(SpanStyle(
                    color = mentionColor,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = mentionWeight
                ))
                builder.append("@")
                builder.append(truncateNickname(mBase))
                builder.pop()

                // Hashtag suffix in lighter color
                if (mSuffix.isNotEmpty()) {
                    builder.pushStyle(SpanStyle(
                        color = mentionColor.copy(alpha = SUFFIX_ALPHA),
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = mentionWeight
                    ))
                    builder.append(mSuffix)
                    builder.pop()
                }

                builder.pop() // background chip
            }
            "hashtag" -> {
                // Render general hashtags like normal content
                builder.pushStyle(plainStyle)
                builder.append(matchText)
                builder.pop()
            }
            else -> {
                if (type == "geohash") {
                    // Style geohash as a link and add click annotation
                    builder.pushStyle(linkStyle)
                    val start = builder.length
                    builder.append(matchText)
                    val end = builder.length
                    val geohash = matchText.removePrefix("#").lowercase()
                    builder.addStringAnnotation(
                        tag = "geohash_click",
                        annotation = geohash,
                        start = start,
                        end = end
                    )
                    builder.pop()
                } else if (type == "url") {
                    // Style URL as a link and add click annotation with the raw text
                    builder.pushStyle(linkStyle)
                    val start = builder.length
                    builder.append(matchText)
                    val end = builder.length
                    builder.addStringAnnotation(
                        tag = "url_click",
                        annotation = matchText,
                        start = start,
                        end = end
                    )
                    builder.pop()
                } else {
                    // Fallback: treat as normal text
                    builder.pushStyle(plainStyle)
                    builder.append(matchText)
                    builder.pop()
                }
            }
        }
        
        lastEnd = range.last + 1
    }
    
    // Add remaining text
    if (lastEnd < content.length) {
        val remainingText = content.substring(lastEnd)
        builder.pushStyle(plainStyle)
        builder.append(remainingText)
        builder.pop()
    }
}
