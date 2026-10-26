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
    REPEATED_ACROSS_USERS,
    REPEATED_CHARACTERS;

    val isSpam: Boolean get() = this != ALLOWED
}

/**
 * Decides whether an incoming public message should be hidden.
 *
 * Each sender gets three token buckets (messages, characters, links; see [SpamFilterConfig])
 * plus three repetition checks: a message that repeats itself internally, a near-copy of the
 * sender's own recent history, and a near-copy of what *other* senders posted recently. The last
 * one catches spam spread over many throwaway identities, which per-sender limits cannot see.
 * Hidden messages never spend tokens, but they do enter the repetition history so a spammer
 * cannot cycle the same text.
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
                recentTexts.clear()
            }
        }

    /** Debug: let spam through and record its verdict in [flagged] so the chat can mark it. */
    @Volatile
    var debugBypass: Boolean = false

    private val _flagged = MutableStateFlow<Map<String, SpamVerdict>>(emptyMap())

    /** Verdicts of spam shown because of [debugBypass], by message ID. */
    val flagged: StateFlow<Map<String, SpamVerdict>> = _flagged.asStateFlow()

    /** A message prepared for comparison: its leading characters and their trigrams. */
    private class Fingerprint(val timeMs: Long, val text: IntArray, val trigrams: IntArray)

    private class SharedEntry(val senderKey: String, val fingerprint: Fingerprint)

    private inner class SenderState {
        val rate = TokenBucketSchedule(
            listOf(config.messageBucket, config.characterBucket, config.linkBucket)
        )
        val history = ArrayDeque<Fingerprint>()
    }

    private val senders = object : LinkedHashMap<String, SenderState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SenderState>) =
            size > config.maxTrackedSenders
    }

    /** Recent texts from every sender, for the cross-user repeat check. */
    private val recentTexts = ArrayDeque<SharedEntry>()

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

            val now = clock()
            val oldestTrusted = now - config.maxBackdateMs
            val time = timestampMs.coerceIn(oldestTrusted, now)
            val state = senders.getOrPut(senderKey) { SenderState() }
            state.rate.prune(oldestTrusted)

            val repetition = config.repetition
            val text = if (isText) RepetitionDetector.normalize(content, repetition.maxAnalyzedLength) else null
            val fingerprint = text?.let { fingerprint(it, time) }
            val verdict = when {
                text != null && hasRepeatedCharacters(text) -> SpamVerdict.REPEATED_CHARACTERS
                text != null && repeatsHistory(state, text.size, fingerprint!!) -> SpamVerdict.REPEATED_MESSAGE
                text != null && repeatsOtherSenders(senderKey, text.size, fingerprint!!) ->
                    SpamVerdict.REPEATED_ACROSS_USERS
                else -> admitRate(state, content, time, isText)
            }

            if (text != null && fingerprint != null) {
                state.history.addLast(fingerprint)
                while (state.history.size > repetition.historySize) state.history.removeFirst()
                if (text.size >= repetition.crossUserMinLength) {
                    recentTexts.addLast(SharedEntry(senderKey, fingerprint))
                    while (recentTexts.size > repetition.crossUserHistorySize) recentTexts.removeFirst()
                }
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
            recentTexts.clear()
        }
        _flagged.value = emptyMap()
    }

    private fun hasRepeatedCharacters(text: IntArray): Boolean {
        val repetition = config.repetition
        return text.size >= repetition.minRepeatedCharactersLength &&
            RepetitionDetector.selfSimilarity(text) >= repetition.repeatedCharactersThreshold
    }

    private fun fingerprint(text: IntArray, timeMs: Long): Fingerprint {
        val max = config.repetition.maxComparedLength
        val compared = if (text.size > max) text.copyOf(max) else text
        return Fingerprint(timeMs, compared, RepetitionDetector.trigrams(compared))
    }

    private fun repeatsHistory(state: SenderState, length: Int, message: Fingerprint): Boolean {
        val repetition = config.repetition
        if (length < repetition.minRepeatedMessageLength) return false
        val copies = state.history.count { isNearCopy(it, message) }
        return copies > repetition.allowedRepeats
    }

    private fun repeatsOtherSenders(senderKey: String, length: Int, message: Fingerprint): Boolean {
        val repetition = config.repetition
        if (length < repetition.crossUserMinLength) return false
        var copies = 0
        for (entry in recentTexts) {
            if (entry.senderKey == senderKey || !isNearCopy(entry.fingerprint, message)) continue
            if (++copies > repetition.crossUserAllowedRepeats) return true
        }
        return false
    }

    private fun isNearCopy(earlier: Fingerprint, message: Fingerprint): Boolean {
        val repetition = config.repetition
        return abs(earlier.timeMs - message.timeMs) <= repetition.historyWindowMs &&
            lengthsCanMatch(earlier.text.size, message.text.size) &&
            RepetitionDetector.trigramOverlap(earlier.trigrams, message.trigrams) >= repetition.minSharedTrigrams &&
            RepetitionDetector.crossSimilarity(
                earlier.text,
                message.text,
                repetition.maxAlignmentPeaks,
                repetition.minPeakRun
            ) >= repetition.repeatedMessageSimilarity
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

        /**
         * True for notices this device generated itself, such as "X favorited you". Every message
         * read off the wire carries the sender's peer ID; only local notices have none. The
         * nickname is no signal, since any peer can call itself "system".
         */
        fun isLocalNotice(message: BitchatMessage): Boolean =
            message.senderPeerID == null && message.sender == "system"

        /** Key for public mesh and mesh channel messages. */
        fun meshSenderKey(message: BitchatMessage): String =
            "mesh:" + (message.senderPeerID ?: message.sender)

        /** Key for geohash messages, by the sender's per-geohash Nostr pubkey. */
        fun nostrSenderKey(pubkeyHex: String): String = "nostr:" + pubkeyHex.lowercase()
    }
}
