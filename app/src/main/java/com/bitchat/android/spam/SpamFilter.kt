package com.bitchat.android.spam

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

enum class SpamVerdict {
    ALLOWED,
    MESSAGE_RATE,
    CHARACTER_RATE,
    LINK_RATE,
    REPEATED_MESSAGE,
    REPEATED_CHARACTERS,
    FORMATTING_ABUSE;

    val isSpam: Boolean get() = this != ALLOWED
}

/**
 * Decides whether an incoming public message should be hidden.
 *
 * Each sender gets three token buckets (messages, characters, links; see [SpamFilterConfig])
 * plus repetition and stateless formatting-abuse checks. Hidden messages never spend tokens.
 * Rate/repetition rejections enter the repetition history so a spammer cannot cycle the same
 * text. Formatting abuse is rejected before repetition analysis or retention in sender history.
 *
 * Verdicts are remembered by message ID, so the same message arriving over BLE and Wi-Fi Aware,
 * or through both geohash handlers, is judged once and counted once.
 */
class SpamFilter(
    config: SpamFilterConfig = SpamFilterConfig(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile
    var enabled: Boolean = true

    /** Changing the limits forgets every sender, so new budgets start full. */
    @Volatile
    var config: SpamFilterConfig = config
        set(value) {
            synchronized(this) {
                field = value
                senders.clear()
                verdicts.clear()
            }
        }

    /** Debug: let spam through and record its verdict in [flagged] so the chat can mark it. */
    @Volatile
    var debugBypass: Boolean = false

    private val _flagged = MutableStateFlow<Map<String, SpamVerdict>>(emptyMap())

    /** Verdicts of spam shown because of [debugBypass], by message ID. */
    val flagged: StateFlow<Map<String, SpamVerdict>> = _flagged.asStateFlow()

    private class HistoryEntry(val timeMs: Long, val text: IntArray)

    private inner class SenderState {
        val rate = TokenBucketSchedule(
            listOf(config.messageBucket, config.characterBucket, config.linkBucket)
        )
        val history = ArrayDeque<HistoryEntry>()
    }

    private val senders = object : LinkedHashMap<String, SenderState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SenderState>) =
            size > config.maxTrackedSenders
    }

    private val verdicts = object : LinkedHashMap<String, SpamVerdict>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpamVerdict>) =
            size > config.maxRememberedVerdicts
    }

    /**
     * Returns true when [message] must be kept out of the chat. With [debugBypass] on, spam is
     * let through and recorded in [flagged] instead.
     */
    fun shouldHide(message: BitchatMessage, senderKey: String): Boolean {
        val verdict = check(message, senderKey)
        if (!verdict.isSpam) return false
        if (!debugBypass) return true
        _flagged.update { current ->
            if (current[message.id] == verdict) current
            else (current + (message.id to verdict)).entries
                .toList()
                .takeLast(MAX_FLAGGED)
                .associate { it.key to it.value }
        }
        return false
    }

    /** Checks a received message; [senderKey] must identify the author stably within a transport. */
    fun check(message: BitchatMessage, senderKey: String): SpamVerdict = check(
        senderKey = senderKey,
        messageId = message.id,
        content = message.content,
        timestampMs = message.timestamp.time,
        isText = message.type == BitchatMessageType.Message
    )

    fun check(
        senderKey: String,
        messageId: String,
        content: String,
        timestampMs: Long,
        isText: Boolean = true
    ): SpamVerdict {
        if (!enabled) return SpamVerdict.ALLOWED
        synchronized(this) {
            verdicts[messageId]?.let { return it }

            if (isText && FormattingAbuseDetector.isAbusive(content)) {
                return SpamVerdict.FORMATTING_ABUSE.also { verdicts[messageId] = it }
            }

            val now = clock()
            val oldestTrusted = now - config.maxBackdateMs
            val time = timestampMs.coerceIn(oldestTrusted, now)
            val state = senders.getOrPut(senderKey) { SenderState() }
            state.rate.prune(oldestTrusted)

            val repetition = config.repetition
            val text = if (isText) RepetitionDetector.normalize(content, repetition.maxAnalyzedLength) else null
            val verdict = when {
                text != null && hasRepeatedCharacters(text) -> SpamVerdict.REPEATED_CHARACTERS
                text != null && repeatsHistory(state, text, time) -> SpamVerdict.REPEATED_MESSAGE
                else -> admitRate(state, content, time, isText)
            }

            if (text != null) {
                state.history.addLast(HistoryEntry(time, text))
                while (state.history.size > repetition.historySize) state.history.removeFirst()
            }
            verdicts[messageId] = verdict
            return verdict
        }
    }

    /** Forgets all senders, e.g. when the filter is toggled or the app is wiped. */
    fun reset() {
        synchronized(this) {
            senders.clear()
            verdicts.clear()
        }
        _flagged.value = emptyMap()
    }

    private fun hasRepeatedCharacters(text: IntArray): Boolean {
        val repetition = config.repetition
        return text.size >= repetition.minRepeatedCharactersLength &&
            RepetitionDetector.selfSimilarity(text) >= repetition.repeatedCharactersThreshold
    }

    private fun repeatsHistory(state: SenderState, text: IntArray, timeMs: Long): Boolean {
        val repetition = config.repetition
        if (text.size < repetition.minRepeatedMessageLength) return false
        val copies = state.history.count { entry ->
            abs(entry.timeMs - timeMs) <= repetition.historyWindowMs &&
                lengthsCanMatch(entry.text.size, text.size) &&
                RepetitionDetector.crossSimilarity(entry.text, text) >= repetition.repeatedMessageSimilarity
        }
        return copies > repetition.allowedRepeats
    }

    // Cross-similarity is bounded by shorter/longer, so skip pairs that can never reach it.
    private fun lengthsCanMatch(a: Int, b: Int): Boolean =
        minOf(a, b).toDouble() / maxOf(a, b) >= config.repetition.repeatedMessageSimilarity

    private fun admitRate(state: SenderState, content: String, timeMs: Long, isText: Boolean): SpamVerdict {
        val characters = if (isText) content.codePointCount(0, content.length).toDouble() else 0.0
        val links = if (isText) LinkCounter.count(content).toDouble() else 0.0
        return when (state.rate.tryAdmit(timeMs, doubleArrayOf(1.0, characters, links))) {
            -1 -> SpamVerdict.ALLOWED
            0 -> SpamVerdict.MESSAGE_RATE
            1 -> SpamVerdict.CHARACTER_RATE
            else -> SpamVerdict.LINK_RATE
        }
    }

    companion object {
        private const val MAX_FLAGGED = 500

        /** Process-wide filter shared by every transport and geohash handler. */
        val shared = SpamFilter()

        /** Key for public mesh and mesh channel messages. */
        fun meshSenderKey(message: BitchatMessage): String =
            "mesh:" + (message.senderPeerID ?: message.sender)

        /** Key for geohash messages, by the sender's per-geohash Nostr pubkey. */
        fun nostrSenderKey(pubkeyHex: String): String = "nostr:" + pubkeyHex.lowercase()
    }
}
