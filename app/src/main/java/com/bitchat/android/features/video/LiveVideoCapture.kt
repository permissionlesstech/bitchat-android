package com.bitchat.android.features.video

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Destination for one live stream's packets. Calls come from the capture's sender thread. */
fun interface LiveVideoTarget {
    fun send(packet: ByteArray)

    /** Packets queued on the busiest outbound link (0 when unknown); drives congestion control. */
    fun backlog(): Int = 0

    /** Packets per second the route can carry without building hidden queues. */
    fun packetBudgetPerSecond(): Int = 24

    companion object {
        fun of(send: (ByteArray) -> Unit, backlog: () -> Int, packetBudget: () -> Int): LiveVideoTarget =
            object : LiveVideoTarget {
                override fun send(packet: ByteArray) = send(packet)
                override fun backlog(): Int = backlog()
                override fun packetBudgetPerSecond(): Int = packetBudget()
            }
    }
}

data class LiveVideoCaptureStats(
    val elapsedMs: Long,
    val framesOffered: Int,
    val framesEncoded: Int,
    val framesDropped: Int,
    val videoUnits: Int,
    val audioUnits: Int,
    val packetsSent: Int,
    val parityPackets: Int,
    val bytesSent: Long,
    /** Mean wall time to convert and queue one picture into the encoder. */
    val meanEncodeMs: Double,
    val videoBitrate: Int,
    val congestionSkippedUnits: Int,
    val congestionEpisodes: Int,
    val maxBacklog: Int,
    val staleDropped: Int
) {
    val sendKbps: Double get() = if (elapsedMs <= 0) 0.0 else bytesSent * 8.0 / elapsedMs
}

/**
 * Encodes camera (or synthetic) pictures to H.264 and microphone audio to AAC, streams them as
 * [VideoBurstPacket]s when [target] is set, and muxes the same encoded samples into an MP4
 * (`video_<streamID>.mp4`) that the caller can send as the reliable final file.
 *
 * With a null [target] it only records, which is how short video clips are made.
 */
class LiveVideoCapture(
    private val outputDirectory: File,
    private val target: LiveVideoTarget?,
    val quality: LiveVideoQuality,
    private val audioEnabled: Boolean,
    val streamID: ByteArray = VideoBurstPacket.makeStreamID(),
    /** Debug Mesh Lab feeds a moving test card and a tone so tests never capture the room. */
    private val synthetic: Boolean = false
) {
    companion object {
        private const val TAG = "LiveVideoCapture"
        private const val MIN_VALID_DURATION_MS = 1_000L
        private const val UNIT_QUEUE_CAPACITY = 512
        private const val PACING_HEADROOM = 1.8
        private const val PACING_BURST_PACKETS = 6
        private const val SENDER_DRAIN_TIMEOUT_MS = 8_000L
        /** Packets older than this in the pacing queue are dropped: they would arrive stale. */
        private const val OUTBOUND_DEADLINE_MS = 1_200L
        private const val DEFAULT_PACKET_BUDGET = 24
        private const val SYNTHETIC_TONE_HZ = 440.0

        fun fileNameFor(streamID: ByteArray): String = "video_${VideoBurstPacket.streamIDHex(streamID)}.mp4"
    }

    private val running = AtomicBoolean(false)
    private val senderRunning = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val framesOffered = AtomicInteger()
    private val framesEncoded = AtomicInteger()
    private val framesDropped = AtomicInteger()
    private val packetsSent = AtomicInteger()
    private val bytesSent = AtomicLong()
    private val encodeNanos = AtomicLong()
    private val congestionSkipped = AtomicInteger()
    private val maxBacklog = AtomicInteger()
    private val staleDropped = AtomicInteger()
    @Volatile private var congestion = LiveVideoCongestionController(quality.videoBitrate)
    private val units = LinkedBlockingQueue<LiveMediaUnit>(UNIT_QUEUE_CAPACITY)
    private val config = LiveVideoConfig(
        LiveVideoCodec.H264_ANNEX_B,
        quality.width,
        quality.height,
        quality.fps,
        quality.videoBitrate / 1_000,
        if (audioEnabled) LiveVideoAudioCodec.AAC_LC_16K_MONO else LiveVideoAudioCodec.NONE
    )
    private val packetizer = VideoStreamPacketizer(streamID, config, quality.fec)

    private var encoder: H264Encoder? = null
    private var aac: AacEncoder? = null
    private var audioRecord: AudioRecord? = null
    private var writer: Mp4Writer? = null
    private var outputFile: File? = null
    private var startNs = 0L
    @Volatile private var lastFrameNs = 0L
    @Volatile private var endPacketDuration: Long? = null
    @Volatile private var canceledStream = false
    @Volatile private var stopRequestedNs = 0L
    private var boosted = false
    private var videoDrainThread: Thread? = null
    private var audioThread: Thread? = null
    private var syntheticThread: Thread? = null
    private var senderThread: Thread? = null

    val isRunning: Boolean get() = running.get()
    val outputWidth: Int get() = quality.width
    val outputHeight: Int get() = quality.height

    fun setMuted(value: Boolean) = muted.set(value)

    fun elapsedMs(): Long = if (startNs == 0L) 0L else (System.nanoTime() - startNs) / 1_000_000L

    fun stats(): LiveVideoCaptureStats = LiveVideoCaptureStats(
        elapsedMs = elapsedMs(),
        framesOffered = framesOffered.get(),
        framesEncoded = framesEncoded.get(),
        framesDropped = framesDropped.get(),
        videoUnits = packetizer.unitsEmitted(LiveVideoTrack.VIDEO),
        audioUnits = packetizer.unitsEmitted(LiveVideoTrack.AUDIO),
        packetsSent = packetsSent.get(),
        parityPackets = packetizer.parityPackets,
        bytesSent = bytesSent.get(),
        meanEncodeMs = framesEncoded.get().takeIf { it > 0 }?.let { encodeNanos.get() / 1e6 / it } ?: 0.0,
        videoBitrate = congestion.bitrate,
        congestionSkippedUnits = congestionSkipped.get(),
        congestionEpisodes = congestion.skipEpisodes,
        maxBacklog = maxBacklog.get(),
        staleDropped = staleDropped.get()
    )

    @SuppressLint("MissingPermission")
    fun start(): File? {
        if (running.get()) return outputFile
        return try {
            outputDirectory.mkdirs()
            val file = File(outputDirectory, fileNameFor(streamID))
            file.delete()
            outputFile = file
            writer = Mp4Writer(file, audioEnabled)
            encoder = H264Encoder(quality.width, quality.height, quality.fps, quality.videoBitrate, quality.keyframeIntervalSeconds)
            if (audioEnabled) {
                aac = AacEncoder(quality.audioBitrate)
                if (!synthetic) audioRecord = openMicrophone()
            }
            startNs = System.nanoTime()
            running.set(true)
            if (target != null) {
                com.bitchat.android.mesh.LiveMediaLinkBoost.acquire()
                boosted = true
                senderRunning.set(true)
                senderThread = Thread(::senderLoop, "bitchat-video-sender").also(Thread::start)
            }
            videoDrainThread = Thread(::videoDrainLoop, "bitchat-video-encoder").also(Thread::start)
            if (audioEnabled) audioThread = Thread(::audioLoop, "bitchat-video-audio").also(Thread::start)
            if (synthetic) syntheticThread = Thread(::syntheticLoop, "bitchat-video-synthetic").also(Thread::start)
            file
        } catch (error: Exception) {
            Log.w(TAG, "Live video capture unavailable: ${error.message}")
            running.set(false)
            senderRunning.set(false)
            releaseCodecs()
            outputFile?.delete()
            outputFile = null
            null
        }
    }

    /** Called from the camera analyzer thread; drops pictures beyond the preset frame rate. */
    fun offerFrame(image: Image, rotationDegrees: Int) {
        if (!running.get() || synthetic) return
        val now = System.nanoTime()
        val interval = 1_000_000_000L / quality.fps
        // Allow 15 % early so a 30 fps camera reliably yields the target rate.
        if (lastFrameNs != 0L && now - lastFrameNs < interval * 85 / 100) return
        lastFrameNs = now
        framesOffered.incrementAndGet()
        val source = runCatching { YuvSource.fromImage(image, rotationDegrees) }.getOrNull() ?: return
        encodeFrame(source, (now - startNs) / 1_000L)
    }

    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    /** Stops capture. Returns the finalized MP4, or null when canceled or too short. */
    fun stop(canceled: Boolean): File? {
        stopRequestedNs = System.nanoTime()
        val wasRunning = running.getAndSet(false)
        if (wasRunning) {
            runCatching { audioRecord?.stop() }
            runCatching { syntheticThread?.join(2_000L) }
            runCatching { audioThread?.join(3_000L) }
            runCatching { encoder?.signalEndOfStream() }
            runCatching { videoDrainThread?.join(3_000L) }
        }
        syntheticThread = null
        audioThread = null
        videoDrainThread = null
        val durationMs = elapsedMs()
        val writerOk = writer?.finish() == true
        val valid = !canceled && writerOk && durationMs >= MIN_VALID_DURATION_MS
        if (target != null) {
            if (valid) endPacketDuration = durationMs else canceledStream = true
            senderRunning.set(false)
            runCatching { senderThread?.join(SENDER_DRAIN_TIMEOUT_MS) }
            if (senderThread?.isAlive == true) senderThread?.interrupt()
            senderThread = null
        }
        releaseCodecs()
        if (boosted) {
            boosted = false
            com.bitchat.android.mesh.LiveMediaLinkBoost.release()
        }
        val file = outputFile
        outputFile = null
        if (!valid) {
            file?.delete()
            return null
        }
        return file
    }

    private fun encodeFrame(source: YuvSource, ptsUs: Long) {
        val videoEncoder = encoder ?: return
        val started = System.nanoTime()
        val queued = runCatching { videoEncoder.encode(source, ptsUs, timeoutUs = 5_000L) }.getOrDefault(false)
        if (queued) {
            encodeNanos.addAndGet(System.nanoTime() - started)
            framesEncoded.incrementAndGet()
        } else {
            framesDropped.incrementAndGet()
        }
    }

    private fun videoDrainLoop() {
        val videoEncoder = encoder ?: return
        try {
            var ended = false
            while (!ended) {
                ended = videoEncoder.drain(10_000L) { frame ->
                    val mp4 = writer
                    if (mp4 != null) {
                        videoEncoder.outputFormat?.let(mp4::setVideoFormat)
                        mp4.writeVideo(frame.annexB, frame.ptsUs, frame.keyframe)
                    }
                    if (target != null) {
                        val unit = LiveMediaUnit(LiveVideoTrack.VIDEO, frame.ptsUs / 1_000L, frame.keyframe, videoEncoder.withParameterSets(frame))
                        if (!units.offer(unit)) Log.w(TAG, "Unit queue full; dropping video frame")
                    }
                }
                // After stop, give the encoder two seconds to deliver end-of-stream.
                val stopNs = stopRequestedNs
                if (!running.get() && stopNs != 0L && System.nanoTime() - stopNs > 2_000_000_000L) break
            }
        } catch (error: Exception) {
            Log.w(TAG, "Video encoder stopped: ${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            AacEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) error("AudioRecord buffer unavailable: $minBuffer")
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            AacEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer * 4, AacEncoder.SAMPLES_PER_FRAME * 16)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord did not initialize")
        }
        record.startRecording()
        return record
    }

    private fun audioLoop() {
        val encoder = aac ?: return
        val pcm = ShortArray(AacEncoder.SAMPLES_PER_FRAME)
        val frameNs = AacEncoder.SAMPLES_PER_FRAME * 1_000_000_000L / AacEncoder.SAMPLE_RATE
        val offsetUs = (System.nanoTime() - startNs) / 1_000L
        var totalSamples = 0L
        var nextSyntheticNs = System.nanoTime() + frameNs
        val emit: (ByteArray, Long) -> Unit = { frame, ptsUs ->
            writer?.writeAudio(frame, ptsUs)
            if (target != null && !units.offer(LiveMediaUnit(LiveVideoTrack.AUDIO, ptsUs / 1_000L, false, frame))) {
                Log.w(TAG, "Unit queue full; dropping audio frame")
            }
        }
        try {
            while (running.get()) {
                val read = if (synthetic) {
                    val wait = nextSyntheticNs - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                    nextSyntheticNs += frameNs
                    pcm.indices.forEach { index ->
                        val phase = 2.0 * PI * SYNTHETIC_TONE_HZ * (totalSamples + index) / AacEncoder.SAMPLE_RATE
                        pcm[index] = (sin(phase) * 6_000).roundToInt().toShort()
                    }
                    pcm.size
                } else {
                    audioRecord?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: break
                }
                if (read <= 0) continue
                if (muted.get()) pcm.fill(0, 0, read)
                val ptsUs = offsetUs + totalSamples * 1_000_000L / AacEncoder.SAMPLE_RATE
                totalSamples += read
                encoder.encode(pcm, read, ptsUs, endOfStream = false, onFrame = emit)
            }
            val ptsUs = offsetUs + totalSamples * 1_000_000L / AacEncoder.SAMPLE_RATE
            encoder.encode(pcm, 0, ptsUs, endOfStream = true, onFrame = emit)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.w(TAG, "Audio capture stopped: ${error.message}")
        }
    }

    private fun syntheticLoop() {
        val pattern = SyntheticVideoPattern(quality.width, quality.height)
        val intervalNs = 1_000_000_000L / quality.fps
        var next = System.nanoTime()
        var index = 0
        try {
            while (running.get()) {
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                next += intervalNs
                framesOffered.incrementAndGet()
                encodeFrame(pattern.frame(index++), (System.nanoTime() - startNs) / 1_000L)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun senderLoop() {
        val sink = target ?: return
        // Pace to the lower of what this preset needs and what the transport can carry; the
        // queue in front of the pacer is then ours to observe, rather than an invisible buffer
        // deep in the Bluetooth stack.
        val presetBytesPerSecond = quality.totalBitrate / 8.0 * (1.0 + quality.fec.parityRatio) * PACING_HEADROOM
        val packetBudget = runCatching { sink.packetBudgetPerSecond() }.getOrDefault(DEFAULT_PACKET_BUDGET)
            .coerceAtLeast(4)
        val bytesPerSecond = minOf(presetBytesPerSecond, packetBudget * VideoBurstPacket.MAX_PACKET_BYTES.toDouble())
        val packetsPerSecond = packetBudget.toDouble()
        val byteBurst = PACING_BURST_PACKETS * VideoBurstPacket.MAX_PACKET_BYTES.toDouble()
        var byteTokens = byteBurst
        var packetTokens = PACING_BURST_PACKETS.toDouble()
        var lastRefill = System.nanoTime()
        val outbound = ArrayDeque<Pair<Long, ByteArray>>()
        val controller = LiveVideoCongestionController(
            maxBitrate = quality.videoBitrate,
            highWater = (packetBudget * 6 / 10).coerceAtLeast(6),
            skipWater = (packetBudget * 3 / 2).coerceAtLeast(18)
        )
        congestion = controller
        fun nowMs() = (System.nanoTime() - startNs) / 1_000_000L
        fun enqueue(packets: List<ByteArray>) {
            val now = nowMs()
            packets.forEach { outbound.addLast(now to it) }
        }
        /** Sends what the token buckets allow; returns ms until the next send is possible. */
        fun drainPaced(finalDrain: Boolean): Long {
            while (outbound.isNotEmpty()) {
                val (queuedAt, packet) = outbound.first()
                if (!finalDrain && nowMs() - queuedAt > OUTBOUND_DEADLINE_MS) {
                    outbound.removeFirst()
                    staleDropped.incrementAndGet()
                    continue
                }
                val now = System.nanoTime()
                val elapsed = (now - lastRefill) / 1e9
                lastRefill = now
                byteTokens = minOf(byteBurst, byteTokens + elapsed * bytesPerSecond)
                packetTokens = minOf(PACING_BURST_PACKETS.toDouble(), packetTokens + elapsed * packetsPerSecond)
                if (byteTokens < packet.size || packetTokens < 1.0) {
                    val byteWait = (packet.size - byteTokens).coerceAtLeast(0.0) / bytesPerSecond
                    val packetWait = (1.0 - packetTokens).coerceAtLeast(0.0) / packetsPerSecond
                    return (maxOf(byteWait, packetWait) * 1_000).toLong().coerceIn(1L, 50L)
                }
                byteTokens -= packet.size
                packetTokens -= 1.0
                outbound.removeFirst()
                runCatching { sink.send(packet) }
                packetsSent.incrementAndGet()
                bytesSent.addAndGet(packet.size.toLong())
            }
            return 20L
        }
        var lastCongestionCheck = 0L
        var awaitingKeyframe = false
        fun admit(unit: LiveMediaUnit): Boolean {
            if (unit.track != LiveVideoTrack.VIDEO) return true
            // While the link is far behind (and until a fresh keyframe once it drains) video is
            // dropped at the source: queued deltas would only arrive stale. Audio keeps flowing.
            if (controller.skipping || (awaitingKeyframe && !unit.keyframe)) {
                congestionSkipped.incrementAndGet()
                return false
            }
            if (unit.keyframe) awaitingKeyframe = false
            return true
        }
        try {
            enqueue(packetizer.start())
            var wait = 0L
            while (senderRunning.get() || units.isNotEmpty()) {
                val unit = units.poll(wait.coerceIn(1L, 20L), TimeUnit.MILLISECONDS)
                val now = nowMs()
                if (now - lastCongestionCheck >= 250L) {
                    lastCongestionCheck = now
                    val linkBacklog = runCatching { sink.backlog() }.getOrDefault(0)
                    val backlog = maxOf(linkBacklog, outbound.size)
                    maxBacklog.accumulateAndGet(backlog, ::maxOf)
                    val decision = controller.onBacklog(backlog, now)
                    encoder?.setBitrate(decision.bitrate)
                    if (decision.skipVideo) awaitingKeyframe = true
                    if (decision.requestKeyframe) encoder?.requestKeyframe()
                }
                if (unit != null && unit.data.isNotEmpty() && admit(unit)) enqueue(packetizer.add(unit, now))
                enqueue(packetizer.tick(nowMs()))
                wait = drainPaced(finalDrain = false)
            }
            val duration = endPacketDuration
            if (duration != null && !canceledStream) {
                enqueue(packetizer.finish(duration, nowMs()))
            } else {
                packetizer.cancel()?.let { enqueue(listOf(it)) }
            }
            val deadline = System.nanoTime() + SENDER_DRAIN_TIMEOUT_MS * 1_000_000L
            while (outbound.isNotEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(drainPaced(finalDrain = true).coerceAtMost(20L))
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.w(TAG, "Live video sender stopped: ${error.message}")
        }
    }

    private fun releaseCodecs() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        encoder?.release()
        encoder = null
        aac?.release()
        aac = null
    }
}
