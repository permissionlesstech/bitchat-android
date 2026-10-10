package com.bitchat.android.ui.media

import com.bitchat.android.features.voice.AudioWaveformExtractor
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.features.voice.resampleWave
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Awaiting the callback makes cancellation discard results from a previous media source. */
internal suspend fun loadVoiceWaveform(
    path: String,
    cached: (String) -> FloatArray? = VoiceWaveformCache::get,
    extract: (String, (FloatArray?) -> Unit) -> Unit = { source, complete ->
        AudioWaveformExtractor.extractAsync(source, sampleCount = 120) { samples ->
            samples?.let { VoiceWaveformCache.put(source, it) }
            complete(samples)
        }
    },
): List<Float>? {
    val samples = cached(path) ?: suspendCancellableCoroutine<FloatArray?> { continuation ->
        extract(path) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }
    return samples?.takeIf { it.isNotEmpty() }?.let {
        (if (it.size == 120) it else resampleWave(it, 120)).toList()
    }
}
