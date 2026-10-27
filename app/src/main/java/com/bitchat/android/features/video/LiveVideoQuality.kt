package com.bitchat.android.features.video

/**
 * Sender presets. Dimensions are portrait and multiples of 16 so every hardware H.264 encoder
 * accepts them without cropping. Low targets multi-hop Bluetooth (roughly the airtime of a
 * live voice burst); Medium suits one or two BLE hops; High assumes Wi-Fi Aware.
 */
enum class LiveVideoQuality(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val keyframeIntervalSeconds: Int,
    val fec: LiveVideoFecPolicy
) {
    LOW(144, 192, 8, 18_000, 12_000, 2, LiveVideoFecPolicy(parityRatio = 0.2, keyframeParityRatio = 0.4, blockWindowMs = 600L)),
    MEDIUM(240, 320, 12, 56_000, 16_000, 2, LiveVideoFecPolicy(parityRatio = 0.2, keyframeParityRatio = 0.35, maxSourceShards = 16)),
    HIGH(480, 640, 24, 360_000, 24_000, 2, LiveVideoFecPolicy(parityRatio = 0.15, keyframeParityRatio = 0.3, maxSourceShards = 32)),

    /** Recorded clips: sized so [LiveVideoLimits.MAX_CLIP_DURATION_MS] fits one file transfer. */
    CLIP(240, 320, 12, 26_000, 12_000, 2, LiveVideoFecPolicy(parityRatio = 0.0, keyframeParityRatio = 0.0));

    val isLivePreset: Boolean get() = this != CLIP

    val totalBitrate: Int get() = videoBitrate + audioBitrate

    companion object {
        fun fromName(value: String?): LiveVideoQuality =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOW
    }
}

object LiveVideoLimits {
    /**
     * Largest video file that fits one mesh file transfer: 256 fragments of at most ~450 B,
     * less the file TLVs, packet header, signature and Noise framing.
     */
    const val MAX_VIDEO_FILE_BYTES: Long = 100L * 1_024

    const val MAX_CLIP_DURATION_MS: Long = 20_000L
    const val MAX_TRANSCODE_SOURCE_MS: Long = 60_000L
}
