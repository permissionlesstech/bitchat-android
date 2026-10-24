package com.bitchat.android.features.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

object VoiceWaveformCache {
    private val map = ConcurrentHashMap<String, FloatArray>()
    fun put(path: String, samples: FloatArray) { map[path] = samples }
    fun get(path: String): FloatArray? = map[path]
}

fun normalizeAmplitudeSample(amp: Int): Float {
    val a = max(0, amp)
    val norm = ln(1.0 + a.toDouble()) / ln(1.0 + 32768.0)
    return norm.toFloat().coerceIn(0f, 1f)
}

fun resampleWave(values: FloatArray, target: Int): FloatArray {
    if (values.isEmpty() || target <= 0) return FloatArray(target) { 0f }
    if (values.size == target) return values
    val out = FloatArray(target)
    val step = (values.size - 1).toFloat() / (target - 1).toFloat()
    var x = 0f
    for (i in 0 until target) {
        val idx = x.toInt()
        val frac = x - idx
        val a = values[idx]
        val b = values[min(values.size - 1, idx + 1)]
        out[i] = (a + (b - a) * frac).coerceIn(0f, 1f)
        x += step
    }
    return out
}

object AudioWaveformExtractor {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun extractAsync(path: String, sampleCount: Int = 120, onComplete: (FloatArray?) -> Unit) {
        scope.launch {
            onComplete(runCatching { extract(path, sampleCount) }.getOrNull())
        }
    }

    private fun extract(path: String, sampleCount: Int): FloatArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
            val fmt = extractor.getTrackFormat(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            mime.startsWith("audio/")
        } ?: return null
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val desiredBins = sampleCount.coerceAtLeast(32)
        val bins = FloatArray(desiredBins) { 0f }
        val counts = IntArray(desiredBins) { 0 }

        val inBuffers = codec.inputBuffers
        val outInfo = MediaCodec.BufferInfo()

        var sawEOS = false
        while (!sawEOS) {
            // Queue input
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex) ?: inBuffers[inIndex]
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    val presentationTimeUs = extractor.sampleTime
                    codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                    extractor.advance()
                }
            }

            // Dequeue output
            var outIndex = codec.dequeueOutputBuffer(outInfo, 10_000)
            while (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)
                if (outBuf != null && outInfo.size > 0) {
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val shortCount = outInfo.size / 2
                    val shorts = ShortArray(shortCount)
                    outBuf.asShortBuffer().get(shorts)

                    // Map this buffer to bins using timestamp range
                    val startUs = outInfo.presentationTimeUs
                    val endUs = startUs + bufferDurationUs(format, outInfo.size)
                    val startBin = binForTime(startUs, durationUs, desiredBins)
                    val endBin = binForTime(endUs, durationUs, desiredBins).coerceAtMost(desiredBins - 1)

                    var idx = 0
                    for (bin in startBin..endBin) {
                        // aggregate portion of buffer to this bin
                        val window = shorts.size / max(1, (endBin - startBin + 1))
                        val begin = idx
                        val finish = min(shorts.size, idx + window)
                        var acc = 0.0
                        var cnt = 0
                        for (i in begin until finish) {
                            acc += abs(shorts[i].toInt())
                            cnt += 1
                        }
                        val avg = if (cnt > 0) (acc / cnt) else 0.0
                        val norm = (avg / 32768.0).coerceIn(0.0, 1.0).toFloat()
                        bins[bin] = max(bins[bin], norm)
                        counts[bin] += 1
                        idx += window
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(outInfo, 0)
            }

            if (outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                sawEOS = true
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        // Smooth + normalize
        var maxVal = 0f
        for (i in bins.indices) {
            if (counts[i] == 0) continue
            maxVal = max(maxVal, bins[i])
        }
        if (maxVal <= 0f) maxVal = 1f
        for (i in bins.indices) {
            bins[i] = (bins[i] / maxVal).coerceIn(0f, 1f)
        }

        return bins
    }

    private fun bufferDurationUs(format: MediaFormat, bytes: Int): Long {
        return try {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val samples = bytes / 2 / max(1, channels)
            (samples * 1_000_000L) / max(1, sampleRate)
        } catch (e: Exception) {
            0L
        }
    }

    private fun binForTime(presentationUs: Long, durationUs: Long, bins: Int): Int {
        if (durationUs <= 0L) return 0
        val frac = presentationUs.toDouble() / durationUs.toDouble()
        return (frac * bins).toInt().coerceIn(0, bins - 1)
    }
}
