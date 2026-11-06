package com.bitchat.android.features.video

/**
 * Sender-side congestion control for a broadcast stream with no receiver feedback.
 *
 * The signal is the depth of the busiest outbound BLE link queue: on a mesh the first hop is
 * the usual bottleneck, and a growing queue is pure added latency. The controller backs the
 * encoder off multiplicatively, probes back up additively once the queue stays short, and when
 * the queue is far behind it skips video until it drains, then restarts from a keyframe so the
 * stream stays live instead of drifting seconds behind.
 */
class LiveVideoCongestionController(
    private val maxBitrate: Int,
    private val minBitrate: Int = maxBitrate * 3 / 10,
    private val highWater: Int = 16,
    private val skipWater: Int = 48,
    private val lowWater: Int = 4
) {
    data class Decision(val bitrate: Int, val skipVideo: Boolean, val requestKeyframe: Boolean)

    private companion object {
        const val DECREASE_INTERVAL_MS = 1_000L
        const val INCREASE_INTERVAL_MS = 1_000L
        const val CALM_BEFORE_INCREASE_MS = 2_000L
    }

    var bitrate: Int = maxBitrate
        private set
    var skipping: Boolean = false
        private set
    var skipEpisodes: Int = 0
        private set

    private var lastDecreaseMs = Long.MIN_VALUE / 2
    private var lastIncreaseMs = Long.MIN_VALUE / 2
    private var calmSinceMs: Long? = null

    fun onBacklog(backlog: Int, nowMs: Long): Decision {
        var keyframe = false
        when {
            backlog >= skipWater -> {
                if (!skipping) skipEpisodes++
                skipping = true
                calmSinceMs = null
                decrease(nowMs, factor = 0.6, force = true)
            }
            backlog >= highWater -> {
                calmSinceMs = null
                decrease(nowMs, factor = 0.8, force = false)
            }
            backlog <= lowWater -> {
                if (skipping) {
                    skipping = false
                    keyframe = true
                }
                val since = calmSinceMs ?: nowMs.also { calmSinceMs = it }
                if (nowMs - since >= CALM_BEFORE_INCREASE_MS && nowMs - lastIncreaseMs >= INCREASE_INTERVAL_MS) {
                    bitrate = minOf(maxBitrate, bitrate + maxBitrate / 12)
                    lastIncreaseMs = nowMs
                }
            }
            else -> calmSinceMs = null
        }
        return Decision(bitrate, skipping, keyframe)
    }

    private fun decrease(nowMs: Long, factor: Double, force: Boolean) {
        if (!force && nowMs - lastDecreaseMs < DECREASE_INTERVAL_MS) return
        bitrate = maxOf(minBitrate, (bitrate * factor).toInt())
        lastDecreaseMs = nowMs
    }
}
