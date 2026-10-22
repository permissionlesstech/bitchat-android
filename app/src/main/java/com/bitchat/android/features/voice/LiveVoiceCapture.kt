package com.bitchat.android.features.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Destination selected for one hold gesture. Calls must be safe from a capture thread. */
fun interface LiveVoiceTarget {
    fun send(packet: ByteArray)
}

internal data class LiveVoiceCaptureStats(
    val queuedPcmFrames: Int,
    val encodedFrames: Int,
    val dataPackets: Int,
    val droppedOversizeFrames: Int,
    val outboundPackets: Int,
    val deliveredPackets: Int
)

/**
 * AudioRecord + MediaCodec capture used only when a live mesh route is available.
 *
 * Encoded access units are streamed as iOS-compatible burst packets while the same units are
 * muxed into an ordinary `.m4a`, which the existing voice-note path sends on release.
 */
internal class LiveVoiceCapture(
    private val outputDirectory: File,
    private val target: LiveVoiceTarget,
    private val burstID: ByteArray = VoiceBurstPacket.makeBurstID(),
    /** Debug Mesh Lab uses a deterministic tone so physical tests never capture ambient audio. */
    private val syntheticPcm: Boolean = false
) {
    companion object {
        private const val TAG = "LiveVoiceCapture"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 16_000
        private const val SAMPLES_PER_AAC_FRAME = 1_024
        private const val MIN_VALID_DURATION_MS = 600L
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val CODEC_INPUT_DEADLINE_MS = 1_000L
        private const val SYNTHETIC_TONE_HZ = 440.0
        private const val SYNTHETIC_TONE_AMPLITUDE = 8_000
        private const val AAC_FRAME_DURATION_NS = 64_000_000L
        private const val OUTBOUND_QUEUE_CAPACITY = 256
        private const val OUTBOUND_DRAIN_TIMEOUT_MS = 10_000L
    }

    private val running = AtomicBoolean(false)
    private val amplitude = AtomicInteger(0)
    private val queuedPcmFrames = AtomicInteger(0)
    private val encodedFrames = AtomicInteger(0)
    private val outboundPackets = AtomicInteger(0)
    private val deliveredPackets = AtomicInteger(0)
    private val outboundQueue = LinkedBlockingQueue<ByteArray>(OUTBOUND_QUEUE_CAPACITY)
    private val senderRunning = AtomicBoolean(false)
    private val packetizer = VoiceBurstPacketizer(burstID)
    private var streamStarted = false
    private var startedAtMs = 0L
    private var totalSamples = 0L

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    private var outputFile: File? = null
    private var captureThread: Thread? = null
    private var senderThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): File? {
        if (running.get()) return outputFile
        return try {
            outputDirectory.mkdirs()
            val burstHex = VoiceBurstPacket.burstIDHex(burstID)
            val file = File(outputDirectory, "voice_$burstHex.m4a")
            if (file.exists()) file.delete()
            outputFile = file

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_AAC_FRAME * 4)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            codec = encoder
            val mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            val record = if (syntheticPcm) {
                null
            } else {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) error("AudioRecord buffer unavailable: $minBuffer")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer * 4, SAMPLES_PER_AAC_FRAME * 16)
                ).also {
                    if (it.state != AudioRecord.STATE_INITIALIZED) {
                        it.release()
                        error("AudioRecord did not initialize")
                    }
                    it.startRecording()
                    if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        it.release()
                        error("AudioRecord did not start")
                    }
                }
            }

            audioRecord = record
            startedAtMs = System.currentTimeMillis()
            senderRunning.set(true)
            senderThread = Thread(::senderLoop, "bitchat-ptt-sender").also(Thread::start)
            running.set(true)
            captureThread = Thread(::captureLoop, "bitchat-ptt-capture").also(Thread::start)
            file
        } catch (error: Exception) {
            Log.w(TAG, "Live capture unavailable; caller will fall back to a voice note: ${error.message}")
            releaseResources()
            outputFile?.delete()
            outputFile = null
            null
        }
    }

    fun pollAmplitude(): Int = amplitude.get()

    fun stats(): LiveVoiceCaptureStats = LiveVoiceCaptureStats(
        queuedPcmFrames = queuedPcmFrames.get(),
        encodedFrames = encodedFrames.get(),
        dataPackets = packetizer.dataPacketCount,
        droppedOversizeFrames = packetizer.droppedFrameCount,
        outboundPackets = outboundPackets.get(),
        deliveredPackets = deliveredPackets.get()
    )

    fun stop(canceled: Boolean): File? {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) {
            runCatching { audioRecord?.stop() }
            runCatching { captureThread?.join(3_000L) }
        }
        captureThread = null

        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        val durationMs = (encodedFrames.get().toLong() * SAMPLES_PER_AAC_FRAME * 1_000L) / SAMPLE_RATE
        val valid = !canceled && elapsedMs >= MIN_VALID_DURATION_MS &&
            durationMs >= MIN_VALID_DURATION_MS && encodedFrames.get() > 0 &&
            (outputFile?.length() ?: 0L) > 0L

        packetizer.flush().forEach(::queueOutbound)
        val controlKind = if (valid) {
            VoiceBurstPacket.Kind.End(packetizer.dataPacketCount, durationMs.coerceAtMost(0xFFFF_FFFFL))
        } else {
            VoiceBurstPacket.Kind.Canceled
        }
        VoiceBurstPacket.create(burstID, packetizer.nextSequence, controlKind)
            ?.encode()
            ?.let(::queueOutbound)
        senderRunning.set(false)
        runCatching { senderThread?.join(OUTBOUND_DRAIN_TIMEOUT_MS) }
        if (senderThread?.isAlive == true) {
            Log.w(TAG, "Live voice sender did not drain before timeout")
            senderThread?.interrupt()
        }
        senderThread = null

        val file = outputFile
        outputFile = null
        if (!valid) {
            file?.delete()
            return null
        }
        return file
    }

    private fun captureLoop() {
        val pcm = ShortArray(SAMPLES_PER_AAC_FRAME)
        var nextSyntheticFrameNs = System.nanoTime() + AAC_FRAME_DURATION_NS
        try {
            while (running.get()) {
                val read = if (syntheticPcm) {
                    val waitNs = nextSyntheticFrameNs - System.nanoTime()
                    if (waitNs > 0L) {
                        Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                    }
                    nextSyntheticFrameNs += AAC_FRAME_DURATION_NS
                    val firstSample = totalSamples
                    pcm.indices.forEach { index ->
                        val phase = 2.0 * Math.PI * SYNTHETIC_TONE_HZ *
                            (firstSample + index).toDouble() / SAMPLE_RATE.toDouble()
                        pcm[index] = (sin(phase) * SYNTHETIC_TONE_AMPLITUDE).roundToInt().toShort()
                    }
                    pcm.size
                } else {
                    audioRecord?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: break
                }
                if (read <= 0) continue
                amplitude.set(pcm.take(read).maxOfOrNull { abs(it.toInt()) } ?: 0)
                queuePcm(pcm, read, endOfStream = false)
                drainEncoder(endOfStream = false)
            }
            queuePcm(pcm, 0, endOfStream = true)
            drainEncoder(endOfStream = true)
        } catch (error: Exception) {
            Log.w(TAG, "Live capture stopped after codec/audio failure: ${error.message}")
        } finally {
            releaseResources()
        }
    }

    private fun queuePcm(samples: ShortArray, count: Int, endOfStream: Boolean) {
        val encoder = codec ?: return
        val deadlineNs = System.nanoTime() + CODEC_INPUT_DEADLINE_MS * 1_000_000L
        while (true) {
            val index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) {
                val input = encoder.getInputBuffer(index)
                    ?: error("AAC encoder returned a null input buffer")
                input.clear()
                input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count)
                val presentationUs = (totalSamples * 1_000_000L) / SAMPLE_RATE
                totalSamples += count
                encoder.queueInputBuffer(
                    index,
                    0,
                    count * 2,
                    presentationUs,
                    if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                )
                if (!endOfStream) queuedPcmFrames.incrementAndGet()
                return
            }
            // Pull encoded output before retrying so transient codec backpressure cannot discard
            // the already-read 64 ms microphone block.
            drainEncoder(endOfStream = false)
            if (System.nanoTime() >= deadlineNs) {
                error("AAC encoder input remained unavailable")
            }
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        var sawEnd = false
        var idlePolls = 0
        while (!sawEnd && (!endOfStream || idlePolls < 100)) {
            when (val index = encoder.dequeueOutputBuffer(info, if (endOfStream) CODEC_TIMEOUT_US else 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    idlePolls++
                    if (!endOfStream) return
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("AAC output format changed twice")
                    muxerTrack = muxer?.addTrack(encoder.outputFormat) ?: -1
                    muxer?.start()
                    muxerStarted = true
                }
                else -> if (index >= 0) {
                    idlePolls = 0
                    val output = encoder.getOutputBuffer(index)
                    if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        if (muxerStarted && muxerTrack >= 0) {
                            muxer?.writeSampleData(muxerTrack, output.duplicate(), info)
                        }
                        val accessUnit = ByteArray(info.size)
                        output.get(accessUnit)
                        emitEncodedFrame(accessUnit)
                    }
                    sawEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun emitEncodedFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        if (!streamStarted) {
            streamStarted = true
            VoiceBurstPacket.create(
                burstID,
                0,
                VoiceBurstPacket.Kind.Start(VoiceBurstCodec.AAC_LC_16K_MONO)
            )?.encode()?.let(::queueOutbound)
        }
        encodedFrames.incrementAndGet()
        packetizer.add(frame).forEach(::queueOutbound)
        // At the target bitrate a packet fits one frame; flushing immediately avoids latency.
        packetizer.flush().forEach(::queueOutbound)
    }

    private fun queueOutbound(packet: ByteArray) {
        outboundQueue.put(packet.copyOf())
        outboundPackets.incrementAndGet()
    }

    private fun senderLoop() {
        try {
            while (senderRunning.get() || outboundQueue.isNotEmpty()) {
                val packet = outboundQueue.poll(100L, TimeUnit.MILLISECONDS) ?: continue
                target.send(packet)
                deliveredPackets.incrementAndGet()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.w(TAG, "Live voice network sender stopped: ${error.message}")
        } finally {
            senderRunning.set(false)
        }
    }

    private fun releaseResources() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        muxerTrack = -1
    }
}
