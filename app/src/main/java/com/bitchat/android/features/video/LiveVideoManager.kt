package com.bitchat.android.features.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.util.Log
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.PttAudioPlayer
import com.bitchat.android.mesh.LiveVideoIngress
import com.bitchat.android.mesh.LiveVideoSink
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.Date
import java.util.UUID

/** What the UI shows for one stream that is currently live. */
data class LiveVideoStreamInfo(
    val messageID: String,
    val senderNickname: String,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val startedAtMs: Long,
    val receivedKbps: Double,
    /** Recent packet loss before FEC, 0.0..1.0. */
    val loss: Double,
    val waitingForKeyframe: Boolean,
    val frozen: Boolean
)

/** A decoded picture; [index] changes with every frame so Compose redraws a reused Bitmap. */
class LiveVideoFrame(val bitmap: Bitmap, val index: Int)

sealed interface LiveVideoEvent {
    val peerID: String
    val streamID: String
    val isPrivate: Boolean

    data class Started(override val peerID: String, override val streamID: String, override val isPrivate: Boolean) : LiveVideoEvent

    data class Finished(
        override val peerID: String,
        override val streamID: String,
        override val isPrivate: Boolean,
        val packets: Int,
        val bytes: Long,
        val recoveredShards: Int,
        val videoUnits: Int,
        val audioUnits: Int,
        val skippedVideoUnits: Int,
        val decodedFrames: Int,
        val meanLuma: Double,
        /** Packet age on arrival (receiver clock minus sender timestamp; includes clock skew). */
        val meanLatencyMs: Long,
        val maxLatencyMs: Long,
        val path: String?
    ) : LiveVideoEvent

    data class Canceled(override val peerID: String, override val streamID: String, override val isPrivate: Boolean) : LiveVideoEvent

    data class Absorbed(
        override val peerID: String,
        override val streamID: String,
        override val isPrivate: Boolean,
        val finalizedPath: String
    ) : LiveVideoEvent
}

/**
 * Receiver for live video: bounded per-stream assembly, decoding for the bubble/full-screen
 * viewer, optional audio, a locally reconstructed MP4, and replacement by the sender's
 * finalized file. Mirrors [com.bitchat.android.features.voice.LiveVoiceManager].
 */
class LiveVideoManager private constructor(private val context: Context) : LiveVideoSink {
    companion object {
        private const val TAG = "LiveVideoManager"
        private const val MAX_CONCURRENT_STREAMS = 4
        private const val MAX_STREAM_BYTES = 24L * 1_024 * 1_024
        private const val INBOUND_BYTES_PER_SECOND = 96_000
        private const val INBOUND_ALLOWANCE_SECONDS = 3.0
        private const val IDLE_TIMEOUT_MS = 4_000L
        private const val TICK_MS = 50L
        private const val INFO_REFRESH_MS = 500L
        private const val FROZEN_AFTER_MS = 1_500L
        private const val FINISHED_TTL_MS = 30 * 60 * 1_000L
        private const val FINISHED_CAP = 16

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: LiveVideoManager? = null

        fun getInstance(context: Context): LiveVideoManager = instance ?: synchronized(this) {
            instance ?: LiveVideoManager(context.applicationContext).also {
                instance = it
                LiveVideoIngress.sink = it
            }
        }

        fun streamIDFromVideoFileName(fileName: String): String? {
            if (!fileName.startsWith("video_")) return null
            val id = fileName.removePrefix("video_").take(16)
            return id.takeIf { value ->
                value.length == 16 && value.all { it.digitToIntOrNull(16) != null }
            }?.lowercase()
        }
    }

    private data class StreamKey(val peerID: String, val isPrivate: Boolean, val streamID: String)

    private inner class Stream(
        val key: StreamKey,
        val nickname: String,
        val message: BitchatMessage,
        var file: File,
        val startedAtMs: Long
    ) {
        val assembler = VideoStreamAssembler()
        var config: LiveVideoConfig? = null
        var decoder: LiveVideoDecoder? = null
        var audio: PttAudioPlayer? = null
        var writer: Mp4Writer? = null
        var lastPacketMs = System.currentTimeMillis()
        var lastFrameMs = 0L
        var receivedBytes = 0L
        var meanLuma = 0.0
        var latencyTotalMs = 0L
        var latencySamples = 0
        var maxLatencyMs = 0L
        val frames = MutableStateFlow<LiveVideoFrame?>(null)
        var frameIndex = 0
        var waitingForKeyframe = true
        var decoderStale = false
    }

    private data class FinishedStream(
        val key: StreamKey,
        val messageID: String,
        val nickname: String,
        val file: File?,
        val timestamp: Date,
        val expiresAtMs: Long
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val streams = linkedMapOf<StreamKey, Stream>()
    private val finished = linkedMapOf<StreamKey, FinishedStream>()
    private val _live = MutableStateFlow<Map<String, LiveVideoStreamInfo>>(emptyMap())
    /** Live streams keyed by chat message ID. */
    val live: StateFlow<Map<String, LiveVideoStreamInfo>> = _live.asStateFlow()
    private val _events = MutableSharedFlow<LiveVideoEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveVideoEvent> = _events.asSharedFlow()
    private val _mutedStreams = MutableStateFlow<Set<String>>(emptySet())
    val mutedStreams: StateFlow<Set<String>> = _mutedStreams.asStateFlow()

    @Volatile private var appForeground = true
    @Volatile private var visiblePrivate: Boolean? = null
    @Volatile private var visiblePeerID: String? = null
    private var tickJob: Job? = null
    private var lastInfoPublishMs = 0L

    init {
        liveDirectory().listFiles()
            ?.filter { it.name.startsWith("video_live_") }
            ?.forEach { it.delete() }
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
        synchronized(this) { streams.values.forEach(::updateAudio) }
    }

    fun showPublicMesh() = setVisible(false, null)
    fun showDirectMessage(peerID: String) = setVisible(true, peerID)
    fun clearVisibleConversation() = setVisible(null, null)

    private fun setVisible(isPrivate: Boolean?, peerID: String?) {
        visiblePrivate = isPrivate
        visiblePeerID = peerID
        synchronized(this) { streams.values.forEach(::updateAudio) }
    }

    fun frames(messageID: String): StateFlow<LiveVideoFrame?>? = synchronized(this) {
        streams.values.firstOrNull { it.message.id == messageID }?.frames
    }

    fun setMuted(messageID: String, muted: Boolean) {
        _mutedStreams.value = if (muted) _mutedStreams.value + messageID else _mutedStreams.value - messageID
        synchronized(this) { streams.values.firstOrNull { it.message.id == messageID }?.let(::updateAudio) }
    }

    @Synchronized
    override fun handleFrame(
        peerID: String,
        nickname: String,
        isPrivate: Boolean,
        payload: ByteArray,
        timestampMs: Long
    ): Boolean {
        val packet = VideoBurstPacket.decode(payload) ?: return false
        val streamHex = VideoBurstPacket.streamIDHex(packet.streamID)
        val key = StreamKey(peerID, isPrivate, streamHex)
        var stream = streams[key]
        if (stream == null) {
            if (key in finished) return true
            if (packet.kind is VideoBurstPacket.Kind.End || packet.kind == VideoBurstPacket.Kind.Canceled) return true
            if (streams.size >= MAX_CONCURRENT_STREAMS) return false
            stream = createStream(key, nickname, timestampMs) ?: return false
            streams[key] = stream
            _events.tryEmit(LiveVideoEvent.Started(peerID, streamHex, isPrivate))
            ensureTicking()
        }
        val now = System.currentTimeMillis()
        stream.receivedBytes += payload.size
        val elapsedSeconds = (now - stream.startedAtMs).coerceAtLeast(0L) / 1_000.0 + INBOUND_ALLOWANCE_SECONDS
        if (stream.receivedBytes > MAX_STREAM_BYTES ||
            stream.receivedBytes > INBOUND_BYTES_PER_SECOND * elapsedSeconds
        ) {
            Log.w(TAG, "Dropping over-quota live video stream")
            finalizeStream(stream)
            return false
        }
        stream.lastPacketMs = now
        val age = (now - timestampMs).coerceAtLeast(0L)
        stream.latencyTotalMs += age
        stream.latencySamples++
        stream.maxLatencyMs = maxOf(stream.maxLatencyMs, age)
        handleOutputs(stream, stream.assembler.onPacket(packet, payload.size, now))
        return true
    }

    @Synchronized
    override fun absorbFinalizedVideo(message: BitchatMessage): Boolean {
        if (message.type != BitchatMessageType.Video) return false
        val streamID = streamIDFromVideoFileName(File(message.content).name) ?: return false
        val peerID = message.senderPeerID ?: return false
        streams.entries.firstOrNull {
            it.key.peerID == peerID && it.key.isPrivate == message.isPrivate && it.key.streamID == streamID
        }?.value?.let(::finalizeStream)
        pruneFinished()
        val entry = finished.entries.firstOrNull {
            it.key.peerID == peerID && it.key.isPrivate == message.isPrivate && it.key.streamID == streamID
        } ?: return false
        val done = entry.value
        val replacement = message.copy(
            id = done.messageID,
            timestamp = done.timestamp,
            sender = done.nickname,
            senderPeerID = peerID,
            isPrivate = message.isPrivate
        )
        if (message.isPrivate) {
            AppStateStore.upsertPrivateMessage(peerID, replacement, isVisible(true, peerID))
        } else {
            AppStateStore.upsertPublicMessage(replacement)
        }
        done.file?.delete()
        finished.remove(entry.key)
        _events.tryEmit(LiveVideoEvent.Absorbed(peerID, streamID, message.isPrivate, message.content))
        return true
    }

    @Synchronized
    fun reset() {
        streams.values.toList().forEach { cancelStream(it, emit = false) }
        finished.clear()
        publishInfo(force = true)
    }

    private fun createStream(key: StreamKey, nickname: String, timestampMs: Long): Stream? {
        val file = File(liveDirectory(), "video_live_${key.streamID}_${key.peerID}_${if (key.isPrivate) "dm" else "mesh"}.mp4")
        file.delete()
        val message = BitchatMessage(
            id = UUID.randomUUID().toString().uppercase(),
            sender = nickname,
            content = file.absolutePath,
            type = BitchatMessageType.Video,
            timestamp = Date(timestampMs),
            isPrivate = key.isPrivate,
            recipientNickname = AppStateStore.nickname.value.takeIf { key.isPrivate },
            senderPeerID = key.peerID
        )
        val stream = Stream(key, nickname, message, file, System.currentTimeMillis())
        // Watching (and relaying) a stream wants the fast link interval too.
        com.bitchat.android.mesh.LiveMediaLinkBoost.acquire()
        // Publish live state before the message so the bubble never flashes as a broken file.
        streams[key] = stream
        publishInfo(force = true)
        if (key.isPrivate) {
            AppStateStore.addPrivateMessage(key.peerID, message, isVisible(true, key.peerID))
        } else {
            AppStateStore.addPublicMessage(message)
        }
        return stream
    }

    private fun handleOutputs(stream: Stream, outputs: List<LiveVideoOutput>) {
        for (output in outputs) {
            when (output) {
                is LiveVideoOutput.Config -> onConfig(stream, output.config)
                is LiveVideoOutput.Video -> onVideo(stream, output)
                is LiveVideoOutput.Audio -> {
                    stream.writer?.writeAudio(output.data, output.ptsMs * 1_000L)
                    stream.audio?.enqueue(listOf(output.data))
                }
                is LiveVideoOutput.VideoSkipped -> Unit
                is LiveVideoOutput.Ended -> {
                    finalizeStream(stream)
                    return
                }
                LiveVideoOutput.Canceled -> {
                    cancelStream(stream, emit = true)
                    return
                }
            }
        }
        publishInfo(force = false)
    }

    private fun onConfig(stream: Stream, config: LiveVideoConfig) {
        if (stream.config != null && stream.config != config) {
            // A resolution change needs a new decoder; the recording keeps its first geometry.
            stream.decoder?.stop()
            stream.decoder = null
        }
        stream.config = config
        if (stream.decoder == null) {
            stream.decoder = LiveVideoDecoder(onFrame = { bitmap -> onDecodedFrame(stream, bitmap) }).also {
                it.configure(config)
            }
        } else {
            stream.decoder?.configure(config)
        }
        updateAudio(stream)
        publishInfo(force = true)
    }

    private fun onVideo(stream: Stream, unit: LiveVideoOutput.Video) {
        val config = stream.config ?: return
        if (unit.keyframe && stream.writer == null) {
            val csd = H264NalUnits.parameterSets(unit.data)
            if (csd != null) {
                stream.writer = Mp4Writer(stream.file, config.hasAudio).also { writer ->
                    writer.setVideoFormat(
                        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                            setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                        }
                    )
                }
            }
        }
        stream.writer?.writeVideo(H264NalUnits.withoutParameterSets(unit.data), unit.ptsMs * 1_000L, unit.keyframe)
        if (unit.keyframe) stream.waitingForKeyframe = false
        // Nobody sees pictures in the background; keep recording but skip the decode work.
        if (appForeground) {
            if (stream.decoderStale) {
                stream.decoder?.resyncAtKeyframe()
                stream.decoderStale = false
            }
            stream.decoder?.submit(unit)
        } else {
            stream.decoderStale = true
        }
    }

    private fun onDecodedFrame(stream: Stream, bitmap: Bitmap) {
        val index = ++stream.frameIndex
        stream.lastFrameMs = System.currentTimeMillis()
        if (index % 8 == 1) stream.meanLuma = runCatching { YuvToBitmap.meanLuma(bitmap) }.getOrDefault(0.0)
        stream.frames.value = LiveVideoFrame(bitmap, index)
    }

    private fun updateAudio(stream: Stream) {
        val wanted = stream.config?.hasAudio == true && appForeground &&
            LiveVoicePreferences.isEnabled(context) &&
            isVisible(stream.key.isPrivate, stream.key.peerID) &&
            stream.message.id !in _mutedStreams.value
        if (wanted && stream.audio == null) {
            stream.audio = PttAudioPlayer()
        } else if (!wanted && stream.audio != null) {
            stream.audio?.stop()
            stream.audio = null
        }
    }

    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val keepGoing = synchronized(this@LiveVideoManager) {
                    val now = System.currentTimeMillis()
                    streams.values.toList().forEach { stream ->
                        if (now - stream.lastPacketMs >= IDLE_TIMEOUT_MS) {
                            handleOutputs(stream, stream.assembler.flush())
                        } else {
                            handleOutputs(stream, stream.assembler.onTick(now))
                        }
                    }
                    publishInfo(force = false)
                    streams.isNotEmpty()
                }
                if (!keepGoing) break
            }
        }
    }

    private fun finalizeStream(stream: Stream) {
        if (streams.remove(stream.key) == null) return
        com.bitchat.android.mesh.LiveMediaLinkBoost.release()
        stream.decoder?.finishAfterDrain()
        stream.audio?.finishAfterDrain()
        val stats = stream.assembler.stats()
        val ok = stream.writer?.finish() == true
        if (!ok) {
            stream.decoder?.stop()
            removeBubble(stream)
            stream.file.delete()
        } else {
            val target = File(stream.file.parentFile, "video_${stream.key.streamID}_${stream.key.peerID}_${if (stream.key.isPrivate) "dm" else "mesh"}.mp4")
            target.delete()
            if (stream.file.renameTo(target)) stream.file = target
            val finalized = stream.message.copy(content = stream.file.absolutePath)
            if (stream.key.isPrivate) {
                AppStateStore.upsertPrivateMessage(stream.key.peerID, finalized, isVisible(true, stream.key.peerID))
            } else {
                AppStateStore.upsertPublicMessage(finalized)
            }
        }
        pruneFinished()
        finished[stream.key] = FinishedStream(
            key = stream.key,
            messageID = stream.message.id,
            nickname = stream.nickname,
            file = stream.file.takeIf { ok },
            timestamp = stream.message.timestamp,
            expiresAtMs = System.currentTimeMillis() + FINISHED_TTL_MS
        )
        _events.tryEmit(
            LiveVideoEvent.Finished(
                peerID = stream.key.peerID,
                streamID = stream.key.streamID,
                isPrivate = stream.key.isPrivate,
                packets = stats.packets,
                bytes = stats.bytes,
                recoveredShards = stats.recoveredShards,
                videoUnits = stats.videoUnits,
                audioUnits = stats.audioUnits,
                skippedVideoUnits = stats.skippedVideoUnits,
                decodedFrames = stream.decoder?.framesDecoded ?: 0,
                meanLuma = stream.meanLuma,
                meanLatencyMs = if (stream.latencySamples == 0) 0L else stream.latencyTotalMs / stream.latencySamples,
                maxLatencyMs = stream.maxLatencyMs,
                path = stream.file.absolutePath.takeIf { ok }
            )
        )
        publishInfo(force = true)
    }

    private fun cancelStream(stream: Stream, emit: Boolean) {
        if (streams.remove(stream.key) == null) return
        com.bitchat.android.mesh.LiveMediaLinkBoost.release()
        stream.decoder?.stop()
        stream.audio?.stop()
        stream.writer?.finish()
        removeBubble(stream)
        stream.file.delete()
        if (emit) {
            _events.tryEmit(LiveVideoEvent.Canceled(stream.key.peerID, stream.key.streamID, stream.key.isPrivate))
        }
        publishInfo(force = true)
    }

    private fun removeBubble(stream: Stream) {
        if (stream.key.isPrivate) {
            AppStateStore.removePrivateMessage(stream.message.id)
        } else {
            AppStateStore.removePublicMessage(stream.message.id)
        }
    }

    private fun publishInfo(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastInfoPublishMs < INFO_REFRESH_MS) return
        lastInfoPublishMs = now
        _live.value = streams.values.associate { stream ->
            val stats = stream.assembler.stats()
            val elapsed = (now - stream.startedAtMs).coerceAtLeast(1L)
            val config = stream.config
            stream.message.id to LiveVideoStreamInfo(
                messageID = stream.message.id,
                senderNickname = stream.nickname,
                width = config?.width ?: LiveVideoQuality.LOW.width,
                height = config?.height ?: LiveVideoQuality.LOW.height,
                hasAudio = config?.hasAudio == true,
                startedAtMs = stream.startedAtMs,
                receivedKbps = stream.receivedBytes * 8.0 / elapsed,
                loss = stats.recentLoss,
                waitingForKeyframe = stream.waitingForKeyframe,
                frozen = stream.lastFrameMs != 0L && now - stream.lastFrameMs > FROZEN_AFTER_MS
            )
        }
    }

    private fun pruneFinished() {
        val now = System.currentTimeMillis()
        finished.entries.removeAll { it.value.expiresAtMs <= now }
        while (finished.size >= FINISHED_CAP) finished.remove(finished.keys.first())
    }

    private fun isVisible(isPrivate: Boolean, peerID: String): Boolean =
        visiblePrivate == isPrivate && (!isPrivate || visiblePeerID == peerID)

    private fun liveDirectory(): File = File(context.cacheDir, "files/incoming").apply { mkdirs() }
}
