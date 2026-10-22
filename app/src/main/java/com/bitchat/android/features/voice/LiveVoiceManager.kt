package com.bitchat.android.features.voice

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.TreeMap
import java.util.UUID

enum class LiveVoiceScope { DIRECT_MESSAGE, PUBLIC_MESH }

sealed interface LiveVoiceEvent {
    val peerID: String
    val burstID: String
    val scope: LiveVoiceScope

    data class Started(
        override val peerID: String,
        override val burstID: String,
        override val scope: LiveVoiceScope
    ) : LiveVoiceEvent

    data class Finished(
        override val peerID: String,
        override val burstID: String,
        override val scope: LiveVoiceScope,
        val dataPackets: Int,
        val frames: Int,
        val bytes: Int,
        val expectedPackets: Int?,
        val missingPackets: Int,
        val path: String
    ) : LiveVoiceEvent

    data class Canceled(
        override val peerID: String,
        override val burstID: String,
        override val scope: LiveVoiceScope
    ) : LiveVoiceEvent

    data class Absorbed(
        override val peerID: String,
        override val burstID: String,
        override val scope: LiveVoiceScope,
        val finalizedPath: String
    ) : LiveVoiceEvent
}

/** Shared phone/Wear receiver: bounded assembly, live playback, bubble state and note absorption. */
class LiveVoiceManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "LiveVoiceManager"
        private const val MAX_CONCURRENT_ASSEMBLIES = 8
        private const val MAX_BURST_BYTES = 384 * 1_024
        private const val INBOUND_BYTES_PER_SECOND = 6_000
        private const val MAX_BUFFERED_PACKETS = 128
        private const val GAP_SKIP_MS = 550L
        private const val IDLE_TIMEOUT_MS = 3_000L
        private const val FINISHED_TTL_MS = 10 * 60 * 1_000L
        private const val FINISHED_CAP = 32

        // The manager constructor immediately narrows this to context.applicationContext.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: LiveVoiceManager? = null

        fun getInstance(context: Context): LiveVoiceManager = instance ?: synchronized(this) {
            instance ?: LiveVoiceManager(context.applicationContext).also { instance = it }
        }

        fun burstIDFromVoiceFileName(fileName: String): String? {
            if (!fileName.startsWith("voice_")) return null
            val id = fileName.removePrefix("voice_").take(16)
            return id.takeIf { value ->
                value.length == 16 && value.all { it.digitToIntOrNull(16) != null }
            }?.lowercase()
        }
    }

    private data class AssemblyKey(
        val peerID: String,
        val scope: LiveVoiceScope,
        val burstID: String
    )

    private class Assembly(
        val key: AssemblyKey,
        val nickname: String,
        val message: BitchatMessage,
        var file: File,
        val output: FileOutputStream,
        val startedAtMs: Long,
        val player: PttAudioPlayer?
    ) {
        val buffered = TreeMap<Int, List<ByteArray>>()
        var nextSequence = 1
        var deliveredFrames = 0
        var deliveredPackets = 0
        var missingPackets = 0
        var receivedBytes = 0
        var endTotalPackets: Int? = null
        var idleJob: Job? = null
        var gapJob: Job? = null
    }

    private data class FinishedBurst(
        val key: AssemblyKey,
        val messageID: String,
        val nickname: String,
        val file: File,
        val timestamp: Date,
        val expiresAtMs: Long,
        val dataPackets: Int,
        val frames: Int,
        val bytes: Int
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val assemblies = linkedMapOf<AssemblyKey, Assembly>()
    private val finishedBursts = linkedMapOf<AssemblyKey, FinishedBurst>()
    private val _liveMessageIDs = MutableStateFlow<Set<String>>(emptySet())
    val liveMessageIDs: StateFlow<Set<String>> = _liveMessageIDs.asStateFlow()
    private val _activePublicTalker = MutableStateFlow<String?>(null)
    val activePublicTalker: StateFlow<String?> = _activePublicTalker.asStateFlow()
    private val _events = MutableSharedFlow<LiveVoiceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveVoiceEvent> = _events.asSharedFlow()

    @Volatile private var appForeground = false
    @Volatile private var visibleScope: LiveVoiceScope? = null
    @Volatile private var visiblePeerID: String? = null
    private var activePlayer: PttAudioPlayer? = null

    init {
        liveDirectory().listFiles()
            ?.filter { it.name.startsWith("voice_live_") }
            ?.forEach { it.delete() }
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
        if (!foreground) {
            synchronized(this) {
                activePlayer?.stop()
                activePlayer = null
                assemblies.values.forEach { assembly -> assembly.player?.stop() }
            }
        }
    }

    fun showPublicMesh() {
        visibleScope = LiveVoiceScope.PUBLIC_MESH
        visiblePeerID = null
    }

    fun showDirectMessage(peerID: String) {
        visibleScope = LiveVoiceScope.DIRECT_MESSAGE
        visiblePeerID = peerID
    }

    fun clearVisibleConversation() {
        visibleScope = null
        visiblePeerID = null
    }

    /** Returns false only when the frame itself violates the live-voice wire/resource contract. */
    @Synchronized
    fun handleFrame(
        peerID: String,
        nickname: String,
        scope: LiveVoiceScope,
        payload: ByteArray,
        timestampMs: Long
    ): Boolean {
        val packet = VoiceBurstPacket.decode(payload) ?: return false
        if (!LiveVoicePreferences.isEnabled(context)) return true
        val burstHex = VoiceBurstPacket.burstIDHex(packet.burstID)
        val key = AssemblyKey(peerID, scope, burstHex)
        var assembly = assemblies[key]
        if (assembly == null) {
            if (packet.kind is VoiceBurstPacket.Kind.End || packet.kind == VoiceBurstPacket.Kind.Canceled) {
                return true
            }
            if (assemblies.size >= MAX_CONCURRENT_ASSEMBLIES) return false
            assembly = createAssembly(key, nickname, timestampMs) ?: return false
            assemblies[key] = assembly
            publishLiveState()
            _events.tryEmit(LiveVoiceEvent.Started(peerID, burstHex, scope))
        }

        assembly.receivedBytes += payload.size
        val elapsedSeconds = ((System.currentTimeMillis() - assembly.startedAtMs).coerceAtLeast(0L) / 1_000.0) + 2.0
        if (
            assembly.receivedBytes > MAX_BURST_BYTES ||
            assembly.receivedBytes > (INBOUND_BYTES_PER_SECOND * elapsedSeconds).toInt()
        ) {
            Log.w(TAG, "Dropping over-quota live voice burst")
            finalizeAssembly(assembly)
            return false
        }
        rescheduleIdle(assembly)

        when (val kind = packet.kind) {
            is VoiceBurstPacket.Kind.Start -> if (kind.codec != VoiceBurstCodec.AAC_LC_16K_MONO) {
                cancelAssembly(assembly)
                return false
            }
            is VoiceBurstPacket.Kind.Frames -> {
                if (packet.sequence < assembly.nextSequence || packet.sequence in assembly.buffered) return true
                if (assembly.buffered.size >= MAX_BUFFERED_PACKETS) return false
                assembly.buffered[packet.sequence] = kind.frames
                drainInOrder(assembly)
            }
            is VoiceBurstPacket.Kind.End -> {
                assembly.endTotalPackets = kind.totalDataPackets
                drainInOrder(assembly)
                finalizeIfComplete(assembly)
            }
            VoiceBurstPacket.Kind.Canceled -> cancelAssembly(assembly)
        }
        return true
    }

    /** Swaps a finalized `voice_<burstID>.m4a` into its existing live row. */
    @Synchronized
    fun absorbFinalizedVoiceNote(message: BitchatMessage): Boolean {
        if (message.type != BitchatMessageType.Audio) return false
        val burstID = burstIDFromVoiceFileName(File(message.content).name) ?: return false
        val messageScope = if (message.isPrivate) LiveVoiceScope.DIRECT_MESSAGE else LiveVoiceScope.PUBLIC_MESH
        val peerID = message.senderPeerID ?: return false
        assemblies.entries.firstOrNull {
            it.key.peerID == peerID && it.key.scope == messageScope && it.key.burstID == burstID
        }?.value?.let(::finalizeAssembly)
        pruneFinished()
        val entry = finishedBursts.entries.firstOrNull {
            it.key.peerID == peerID && it.key.scope == messageScope && it.key.burstID == burstID
        } ?: return false
        val finished = entry.value
        val replacement = message.copy(
            id = finished.messageID,
            timestamp = finished.timestamp,
            sender = finished.nickname,
            senderPeerID = peerID,
            isPrivate = messageScope == LiveVoiceScope.DIRECT_MESSAGE
        )
        if (messageScope == LiveVoiceScope.DIRECT_MESSAGE) {
            AppStateStore.upsertPrivateMessage(peerID, replacement, isVisible(messageScope, peerID))
        } else {
            AppStateStore.upsertPublicMessage(replacement)
        }
        finished.file.delete()
        finishedBursts.remove(entry.key)
        _events.tryEmit(LiveVoiceEvent.Absorbed(peerID, burstID, messageScope, message.content))
        return true
    }

    @Synchronized
    fun reset() {
        assemblies.values.toList().forEach(::cancelAssembly)
        activePlayer?.stop()
        activePlayer = null
        finishedBursts.clear()
        publishLiveState()
    }

    private fun createAssembly(key: AssemblyKey, nickname: String, timestampMs: Long): Assembly? {
        val file = File(
            liveDirectory(),
            "voice_live_${key.burstID}_${key.peerID}_${if (key.scope == LiveVoiceScope.DIRECT_MESSAGE) "dm" else "mesh"}.aac"
        )
        file.parentFile?.mkdirs()
        file.delete()
        val output = runCatching { FileOutputStream(file) }.getOrNull() ?: return null
        val message = BitchatMessage(
            id = UUID.randomUUID().toString().uppercase(),
            sender = nickname,
            content = file.absolutePath,
            type = BitchatMessageType.Audio,
            timestamp = Date(timestampMs),
            isPrivate = key.scope == LiveVoiceScope.DIRECT_MESSAGE,
            recipientNickname = AppStateStore.nickname.value.takeIf { key.scope == LiveVoiceScope.DIRECT_MESSAGE },
            senderPeerID = key.peerID
        )
        if (key.scope == LiveVoiceScope.DIRECT_MESSAGE) {
            AppStateStore.addPrivateMessage(key.peerID, message, isVisible(key.scope, key.peerID))
        } else {
            AppStateStore.addPublicMessage(message)
        }
        val player = if (canAutoplay(key)) {
            activePlayer?.stop()
            PttAudioPlayer().also { activePlayer = it }
        } else null
        return Assembly(key, nickname, message, file, output, System.currentTimeMillis(), player)
    }

    private fun drainInOrder(assembly: Assembly) {
        while (true) {
            val frames = assembly.buffered.remove(assembly.nextSequence)
            if (frames != null) {
                frames.forEach { frame ->
                    runCatching { assembly.output.write(AdtsFramer.frame(frame)) }
                }
                runCatching { assembly.output.flush() }
                assembly.deliveredFrames += frames.size
                assembly.deliveredPackets++
                assembly.player?.enqueue(frames)
                assembly.nextSequence = (assembly.nextSequence + 1) and 0xFFFF
                assembly.gapJob?.cancel()
                assembly.gapJob = null
                continue
            }
            if (assembly.buffered.isNotEmpty() && assembly.gapJob == null) {
                val key = assembly.key
                assembly.gapJob = scope.launch {
                    delay(GAP_SKIP_MS)
                    synchronized(this@LiveVoiceManager) {
                        val current = assemblies[key] ?: return@synchronized
                        current.buffered.firstKey()?.let { skipGap(current, it) }
                        current.gapJob = null
                        drainInOrder(current)
                        finalizeIfComplete(current)
                    }
                }
            }
            return
        }
    }

    private fun finalizeIfComplete(assembly: Assembly) {
        val total = assembly.endTotalPackets ?: return
        if (assembly.nextSequence > total) finalizeAssembly(assembly)
    }

    private fun finalizeAssembly(assembly: Assembly) {
        if (assemblies.remove(assembly.key) == null) return
        assembly.idleJob?.cancel()
        assembly.gapJob?.cancel()
        while (assembly.buffered.isNotEmpty()) {
            skipGap(assembly, assembly.buffered.firstKey())
            drainInOrder(assembly)
            if (assembly.gapJob != null) {
                assembly.gapJob?.cancel()
                assembly.gapJob = null
            }
        }
        runCatching { assembly.output.close() }
        assembly.player?.finishAfterDrain()
        val missingPackets = assembly.endTotalPackets
            ?.let { total -> (total - assembly.deliveredPackets).coerceAtLeast(assembly.missingPackets) }
            ?: assembly.missingPackets
        if (assembly.deliveredFrames == 0) {
            removeBubble(assembly)
            assembly.file.delete()
            publishLiveState()
            return
        }
        val fallback = File(
            assembly.file.parentFile,
            "voice_${assembly.key.burstID}_${assembly.key.peerID}_${if (assembly.key.scope == LiveVoiceScope.DIRECT_MESSAGE) "dm" else "mesh"}.aac"
        )
        fallback.delete()
        if (assembly.file.renameTo(fallback)) assembly.file = fallback
        val finalizedMessage = assembly.message.copy(content = assembly.file.absolutePath)
        if (assembly.key.scope == LiveVoiceScope.DIRECT_MESSAGE) {
            AppStateStore.upsertPrivateMessage(
                assembly.key.peerID,
                finalizedMessage,
                isVisible(assembly.key.scope, assembly.key.peerID)
            )
        } else {
            AppStateStore.upsertPublicMessage(finalizedMessage)
        }
        pruneFinished()
        finishedBursts[assembly.key] = FinishedBurst(
            key = assembly.key,
            messageID = assembly.message.id,
            nickname = assembly.nickname,
            file = assembly.file,
            timestamp = assembly.message.timestamp,
            expiresAtMs = System.currentTimeMillis() + FINISHED_TTL_MS,
            dataPackets = assembly.deliveredPackets,
            frames = assembly.deliveredFrames,
            bytes = assembly.receivedBytes
        )
        _events.tryEmit(
            LiveVoiceEvent.Finished(
                assembly.key.peerID,
                assembly.key.burstID,
                assembly.key.scope,
                assembly.deliveredPackets,
                assembly.deliveredFrames,
                assembly.receivedBytes,
                assembly.endTotalPackets,
                missingPackets,
                assembly.file.absolutePath
            )
        )
        publishLiveState()
    }

    private fun cancelAssembly(assembly: Assembly) {
        if (assemblies.remove(assembly.key) == null) return
        assembly.idleJob?.cancel()
        assembly.gapJob?.cancel()
        assembly.player?.stop()
        runCatching { assembly.output.close() }
        removeBubble(assembly)
        assembly.file.delete()
        _events.tryEmit(
            LiveVoiceEvent.Canceled(assembly.key.peerID, assembly.key.burstID, assembly.key.scope)
        )
        publishLiveState()
    }

    private fun skipGap(assembly: Assembly, nextAvailableSequence: Int) {
        val distance = (nextAvailableSequence - assembly.nextSequence) and 0xFFFF
        if (distance in 1..0x7FFF) assembly.missingPackets += distance
        assembly.nextSequence = nextAvailableSequence
    }

    private fun removeBubble(assembly: Assembly) {
        if (assembly.key.scope == LiveVoiceScope.DIRECT_MESSAGE) {
            AppStateStore.removePrivateMessage(assembly.message.id)
        } else {
            AppStateStore.removePublicMessage(assembly.message.id)
        }
    }

    private fun rescheduleIdle(assembly: Assembly) {
        assembly.idleJob?.cancel()
        val key = assembly.key
        assembly.idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            synchronized(this@LiveVoiceManager) {
                assemblies[key]?.let(::finalizeAssembly)
            }
        }
    }

    private fun publishLiveState() {
        _liveMessageIDs.value = assemblies.values.mapTo(linkedSetOf()) { it.message.id }
        _activePublicTalker.value = assemblies.values
            .firstOrNull { it.key.scope == LiveVoiceScope.PUBLIC_MESH }
            ?.nickname
    }

    private fun pruneFinished() {
        val now = System.currentTimeMillis()
        finishedBursts.entries.removeAll { it.value.expiresAtMs <= now }
        while (finishedBursts.size >= FINISHED_CAP) {
            val oldest = finishedBursts.minByOrNull { it.value.expiresAtMs }?.key ?: break
            finishedBursts.remove(oldest)
        }
    }

    private fun canAutoplay(key: AssemblyKey): Boolean =
        LiveVoicePreferences.isEnabled(context) && appForeground && isVisible(key.scope, key.peerID)

    private fun isVisible(scope: LiveVoiceScope, peerID: String): Boolean =
        visibleScope == scope && (scope == LiveVoiceScope.PUBLIC_MESH || visiblePeerID == peerID)

    private fun liveDirectory(): File = File(context.cacheDir, "files/incoming").apply { mkdirs() }
}
