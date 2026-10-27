package com.bitchat.android.features.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encoded output of [H264Encoder]; [annexB] is exactly what the codec produced. */
class EncodedVideoFrame(val annexB: ByteArray, val ptsUs: Long, val keyframe: Boolean)

/**
 * H.264 Constrained Baseline encoder fed with YUV pictures through `getInputImage`, which works
 * for every flexible-YUV encoder regardless of its native planar/semi-planar layout.
 *
 * [encode] and [drain] may be called from different threads (MediaCodec allows it).
 */
class H264Encoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    bitrate: Int,
    keyframeIntervalSeconds: Int
) {
    companion object {
        private const val TAG = "H264Encoder"
    }

    private val codec: MediaCodec
    private val scaler = YuvScaler(width, height)
    @Volatile private var parameterSets: ByteArray? = null
    @Volatile var outputFormat: MediaFormat? = null
        private set
    @Volatile var currentBitrate: Int = bitrate
        private set

    init {
        val format = { withProfile: Boolean ->
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSeconds)
                if (withProfile) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
        }
        codec = createConfigured(format)
        codec.start()
    }

    private fun createConfigured(format: (Boolean) -> MediaFormat): MediaCodec {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .filter { info ->
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        ?.isSizeSupported(width, height) == true
                }.getOrDefault(false)
            }
            .sortedBy { info -> if (info.isHardwareAcceleratedCompat()) 0 else 1 }
        for (info in candidates) {
            val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val attempt = format(true)
            // CBR keeps each second's airtime predictable on a link with no spare capacity.
            if (capabilities.encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) == true
            ) {
                attempt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: continue
            val configured = runCatching {
                codec.configure(attempt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }.recoverCatching {
                // Some encoders reject an explicit profile/level or bitrate mode; retry plainly.
                codec.reset()
                codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            if (configured.isSuccess) {
                Log.i(TAG, "Using ${info.name} for ${width}x$height")
                return codec
            }
            codec.release()
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /** Queues one picture; returns false when the encoder had no free input (frame dropped). */
    fun encode(source: YuvSource, ptsUs: Long, timeoutUs: Long = 0L): Boolean {
        val index = codec.dequeueInputBuffer(timeoutUs)
        if (index < 0) return false
        val image = codec.getInputImage(index)
        if (image == null) {
            codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
            return false
        }
        scaler.writeInto(source, image)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
        return true
    }

    fun signalEndOfStream() {
        val index = codec.dequeueInputBuffer(100_000L)
        if (index >= 0) codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    fun requestKeyframe() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(bitrate: Int) {
        if (bitrate == currentBitrate) return
        currentBitrate = bitrate
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) }
    }

    /**
     * Pulls available output. Returns true once end-of-stream was seen. [onFrame] receives the
     * raw access unit; [withParameterSets] prefixes SPS/PPS to keyframes for the wire.
     * [waitUs] applies to each poll, so a positive value also waits for more output.
     */
    fun drain(waitUs: Long, onFrame: (EncodedVideoFrame) -> Unit): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, waitUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also(buffer::get)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            parameterSets = bytes
                        } else {
                            onFrame(EncodedVideoFrame(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (endOfStream) return true
                }
            }
        }
    }

    /** SPS/PPS + IDR for the wire, so any keyframe is decodable on its own. */
    fun withParameterSets(frame: EncodedVideoFrame): ByteArray {
        if (!frame.keyframe) return H264NalUnits.withoutFiller(frame.annexB)
        if (H264NalUnits.containsType(frame.annexB, H264NalUnits.TYPE_SPS)) return frame.annexB
        val csd = parameterSets ?: outputFormat?.let { format ->
            val sps = format.getByteBuffer("csd-0")?.toByteArray() ?: return@let null
            val pps = format.getByteBuffer("csd-1")?.toByteArray() ?: return@let null
            sps + pps
        } ?: return frame.annexB
        return csd + H264NalUnits.withoutFiller(frame.annexB)
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/** AAC-LC 16 kHz mono encoder producing raw (ADTS-less) access units, as live voice does. */
class AacEncoder(bitrate: Int) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_FRAME = 1_024
        private const val TIMEOUT_US = 10_000L

        /** AudioSpecificConfig for AAC-LC, 16 kHz (index 8), mono. */
        fun mediaFormat(): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
            }
    }

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_FRAME * 4)
            },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        start()
    }

    /** Queues PCM; blocks briefly on backpressure while draining output through [onFrame]. */
    fun encode(samples: ShortArray, count: Int, ptsUs: Long, endOfStream: Boolean, onFrame: (ByteArray, Long) -> Unit) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = codec.getInputBuffer(index) ?: error("AAC encoder returned a null input buffer")
                input.clear()
                input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count)
                codec.queueInputBuffer(index, 0, count * 2, ptsUs, if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                drain(endOfStream, onFrame)
                return
            }
            drain(false, onFrame)
            if (System.nanoTime() > deadline) error("AAC encoder input remained unavailable")
        }
    }

    private fun drain(endOfStream: Boolean, onFrame: (ByteArray, Long) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++idle > 100) return
                continue
            }
            if (index < 0) continue
            idle = 0
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                onFrame(ByteArray(info.size).also(buffer::get), info.presentationTimeUs)
            }
            val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(index, false)
            if (ended) return
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/**
 * MediaMuxer wrapper that tolerates samples arriving before the video format is known, enforces
 * monotonic timestamps per track, and never throws.
 */
class Mp4Writer(private val file: File, private val includeAudio: Boolean) {
    companion object {
        private const val TAG = "Mp4Writer"
        private const val MAX_EARLY_SAMPLES = 256
    }

    private class Sample(val video: Boolean, val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private val early = ArrayDeque<Sample>()
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    var videoSamples = 0
        private set
    var lastPtsUs = 0L
        private set

    @Synchronized
    fun setVideoFormat(format: MediaFormat) {
        if (started) return
        try {
            file.parentFile?.mkdirs()
            file.delete()
            val mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrack = mediaMuxer.addTrack(format)
            if (includeAudio) audioTrack = mediaMuxer.addTrack(AacEncoder.mediaFormat())
            mediaMuxer.start()
            muxer = mediaMuxer
            started = true
            while (early.isNotEmpty()) {
                val sample = early.removeFirst()
                write(sample.video, sample.data, sample.ptsUs, sample.keyframe)
            }
        } catch (error: Exception) {
            Log.w(TAG, "MP4 muxer unavailable: ${error.message}")
            runCatching { muxer?.release() }
            muxer = null
        }
    }

    @Synchronized
    fun writeVideo(data: ByteArray, ptsUs: Long, keyframe: Boolean) = write(true, data, ptsUs, keyframe)

    @Synchronized
    fun writeAudio(data: ByteArray, ptsUs: Long) {
        if (includeAudio) write(false, data, ptsUs, false)
    }

    private fun write(video: Boolean, data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (!started) {
            if (video && early.size < MAX_EARLY_SAMPLES) early.addLast(Sample(true, data, ptsUs, keyframe))
            else if (!video && early.size < MAX_EARLY_SAMPLES) early.addLast(Sample(false, data, ptsUs, false))
            return
        }
        val mediaMuxer = muxer ?: return
        val track = if (video) videoTrack else audioTrack
        if (track < 0) return
        val last = if (video) lastVideoPts else lastAudioPts
        if (ptsUs <= last) return
        // MP4 requires the first video sample to be a sync sample.
        if (video && videoSamples == 0 && !keyframe) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        try {
            mediaMuxer.writeSampleData(track, ByteBuffer.wrap(data), info)
            if (video) {
                lastVideoPts = ptsUs
                videoSamples++
            } else {
                lastAudioPts = ptsUs
            }
            lastPtsUs = maxOf(lastPtsUs, ptsUs)
        } catch (error: Exception) {
            Log.w(TAG, "Dropping sample: ${error.message}")
        }
    }

    /** Finalizes the file; returns false (and deletes it) when nothing playable was written. */
    @Synchronized
    fun finish(): Boolean {
        val mediaMuxer = muxer
        muxer = null
        val ok = mediaMuxer != null && videoSamples > 0 && runCatching { mediaMuxer.stop() }.isSuccess
        runCatching { mediaMuxer?.release() }
        if (!ok) file.delete()
        return ok
    }
}

internal fun ByteBuffer.toByteArray(): ByteArray {
    val copy = duplicate()
    copy.rewind()
    return ByteArray(copy.remaining()).also(copy::get)
}

private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= 29) isHardwareAccelerated else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
