package com.bitchat.android.features.video

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Re-encodes any local video into a small H.264/AAC MP4 that fits one mesh file transfer.
 *
 * Bitrate is derived from the duration and byte budget; resolution and frame rate follow the
 * bitrate; the source aspect ratio is kept (rounded to 16-pixel blocks). Sources longer than
 * [LiveVideoLimits.MAX_TRANSCODE_SOURCE_MS] are trimmed. Audio is dropped when the budget
 * cannot afford intelligible speech alongside the picture.
 */
object VideoTranscoder {
    private const val TAG = "VideoTranscoder"
    private const val TIMEOUT_US = 10_000L
    private const val AUDIO_BITRATE = 12_000
    private const val CONTAINER_HEADROOM = 0.92
    private const val MAX_ATTEMPTS = 3

    data class Result(val file: File, val durationMs: Long, val trimmed: Boolean, val hasAudio: Boolean)

    private class Plan(val width: Int, val height: Int, val fps: Int, val videoBitrate: Int, val withAudio: Boolean)

    private class Sample(val video: Boolean, val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    fun transcode(
        context: Context,
        source: Uri,
        output: File,
        budgetBytes: Long = LiveVideoLimits.MAX_VIDEO_FILE_BYTES,
        maxDurationMs: Long = LiveVideoLimits.MAX_TRANSCODE_SOURCE_MS
    ): Result? = try {
        transcodeOrThrow(context, source, output, budgetBytes, maxDurationMs)
    } catch (error: Exception) {
        Log.w(TAG, "Transcode failed: ${error.message}")
        output.delete()
        null
    }

    private fun transcodeOrThrow(
        context: Context,
        source: Uri,
        output: File,
        budgetBytes: Long,
        maxDurationMs: Long
    ): Result? {
        val probe = MediaExtractor().apply { setDataSource(context, source, null) }
        val videoIndex = (0 until probe.trackCount).firstOrNull { probe.mime(it).startsWith("video/") }
        val audioIndex = (0 until probe.trackCount).firstOrNull { probe.mime(it).startsWith("audio/") }
        if (videoIndex == null) {
            probe.release()
            return null
        }
        val videoFormat = probe.getTrackFormat(videoIndex)
        probe.release()
        val sourceDurationMs = videoFormat.longOrNull(MediaFormat.KEY_DURATION)?.div(1_000L) ?: maxDurationMs
        val durationMs = sourceDurationMs.coerceIn(500L, maxDurationMs)
        val rotation = videoFormat.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
        val sourceWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val sourceHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val (uprightWidth, uprightHeight) =
            if (rotation % 180 != 0) sourceHeight to sourceWidth else sourceWidth to sourceHeight

        var totalBits = budgetBytes * 8.0 * CONTAINER_HEADROOM
        repeat(MAX_ATTEMPTS) { attempt ->
            val plan = plan(totalBits, durationMs, uprightWidth, uprightHeight, audioIndex != null)
            val samples = mutableListOf<Sample>()
            val format = encodeVideo(context, source, videoIndex, rotation, durationMs, plan, samples) ?: return null
            if (plan.withAudio && audioIndex != null) {
                runCatching { encodeAudio(context, source, audioIndex, durationMs, samples) }
                    .onFailure { Log.w(TAG, "Audio skipped: ${it.message}") }
            }
            val hasAudio = samples.any { !it.video }
            val writer = Mp4Writer(output, hasAudio)
            writer.setVideoFormat(format)
            samples.sortedBy { it.ptsUs }.forEach { sample ->
                if (sample.video) writer.writeVideo(sample.data, sample.ptsUs, sample.keyframe)
                else writer.writeAudio(sample.data, sample.ptsUs)
            }
            if (!writer.finish()) return null
            val size = output.length()
            Log.i(TAG, "Attempt ${attempt + 1}: ${plan.width}x${plan.height}@${plan.fps} ${plan.videoBitrate}bps -> $size B")
            if (size <= budgetBytes) {
                return Result(output, durationMs, sourceDurationMs > maxDurationMs, hasAudio)
            }
            totalBits *= budgetBytes.toDouble() / size * 0.9
        }
        output.delete()
        return null
    }

    private fun plan(totalBits: Double, durationMs: Long, width: Int, height: Int, hasAudio: Boolean): Plan {
        val totalBps = (totalBits / (durationMs / 1_000.0)).toInt()
        val withAudio = hasAudio && totalBps >= 30_000
        val videoBps = (totalBps - if (withAudio) AUDIO_BITRATE else 0).coerceIn(6_000, 600_000)
        val longSide = when {
            videoBps >= 200_000 -> 480
            videoBps >= 60_000 -> 320
            else -> 192
        }
        val fps = when {
            videoBps >= 120_000 -> 24
            videoBps >= 40_000 -> 15
            videoBps >= 18_000 -> 10
            else -> 6
        }
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val (targetWidth, targetHeight) = if (aspect >= 1.0) {
            longSide to roundTo16(longSide / aspect)
        } else {
            roundTo16(longSide * aspect) to longSide
        }
        return Plan(targetWidth, targetHeight, fps, videoBps, withAudio)
    }

    private fun roundTo16(value: Double): Int = ((value / 16.0).roundToInt() * 16).coerceAtLeast(64)

    private fun encodeVideo(
        context: Context,
        source: Uri,
        trackIndex: Int,
        rotation: Int,
        durationMs: Long,
        plan: Plan,
        samples: MutableList<Sample>
    ): MediaFormat? {
        val extractor = MediaExtractor().apply { setDataSource(context, source, null) }
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex).apply {
            // Ask for flexible YUV so getOutputImage works on every vendor decoder.
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
        }
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        val encoder = H264Encoder(plan.width, plan.height, plan.fps, plan.videoBitrate, keyframeIntervalSeconds = 3)
        val endUs = durationMs * 1_000L
        val frameUs = 1_000_000L / plan.fps
        var nextFrameUs = 0L
        var inputDone = false
        var decodeDone = false
        val info = MediaCodec.BufferInfo()
        val collect: (EncodedVideoFrame) -> Unit = { frame ->
            samples += Sample(true, frame.annexB, frame.ptsUs, frame.keyframe)
        }
        try {
            while (!decodeDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out >= 0) {
                    val pts = info.presentationTimeUs
                    if (info.size > 0 && pts <= endUs && pts >= nextFrameUs) {
                        decoder.getOutputImage(out)?.use { image ->
                            val picture = YuvSource.fromImage(image, rotation)
                            while (!encoder.encode(picture, pts, timeoutUs = TIMEOUT_US)) {
                                encoder.drain(0L, collect)
                            }
                        }
                        nextFrameUs = maxOf(nextFrameUs + frameUs, pts)
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                }
                encoder.drain(0L, collect)
            }
            encoder.signalEndOfStream()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!encoder.drain(TIMEOUT_US, collect) && System.nanoTime() < deadline) Unit
            return encoder.outputFormat
        } finally {
            encoder.release()
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
    }

    private fun encodeAudio(
        context: Context,
        source: Uri,
        trackIndex: Int,
        durationMs: Long,
        samples: MutableList<Sample>
    ) {
        val extractor = MediaExtractor().apply { setDataSource(context, source, null) }
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        val encoder = AacEncoder(AUDIO_BITRATE)
        val endUs = durationMs * 1_000L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
        var channels = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
        val resampler = MonoResampler()
        val frame = ShortArray(AacEncoder.SAMPLES_PER_FRAME)
        var frameFill = 0
        var encodedSamples = 0L
        val collect: (ByteArray, Long) -> Unit = { data, pts -> samples += Sample(false, data, pts, false) }
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                    channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                    val encoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT) error("Unsupported PCM encoding $encoding")
                } else if (out >= 0) {
                    val buffer = decoder.getOutputBuffer(out)
                    if (buffer != null && info.size > 0 && info.presentationTimeUs <= endUs) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val pcm = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val interleaved = ShortArray(pcm.remaining()).also(pcm::get)
                        resampler.push(interleaved, channels, sampleRate) { value ->
                            frame[frameFill++] = value
                            if (frameFill == frame.size) {
                                encoder.encode(frame, frameFill, encodedSamples * 1_000_000L / AacEncoder.SAMPLE_RATE, false, collect)
                                encodedSamples += frameFill
                                frameFill = 0
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            encoder.encode(frame, frameFill, encodedSamples * 1_000_000L / AacEncoder.SAMPLE_RATE, true, collect)
        } finally {
            encoder.release()
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
    }

    /** Downmixes interleaved PCM to mono and linearly resamples it to 16 kHz. */
    private class MonoResampler {
        private var position = 0.0
        private var previous = 0

        fun push(interleaved: ShortArray, channels: Int, sampleRate: Int, emit: (Short) -> Unit) {
            val step = sampleRate.toDouble() / AacEncoder.SAMPLE_RATE
            val frames = interleaved.size / channels.coerceAtLeast(1)
            for (index in 0 until frames) {
                var sum = 0
                for (channel in 0 until channels) sum += interleaved[index * channels + channel]
                val current = sum / channels
                while (position <= 1.0) {
                    emit((previous + (current - previous) * position).roundToInt().toShort())
                    position += step
                }
                position -= 1.0
                previous = current
            }
        }
    }

    private fun MediaExtractor.mime(track: Int): String = getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null
}
