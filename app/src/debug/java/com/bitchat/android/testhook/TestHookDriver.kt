package com.bitchat.android.testhook

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.features.voice.LiveVoiceEvent
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.LiveVoiceScope
import com.bitchat.android.features.voice.LiveVoiceTarget
import com.bitchat.android.features.voice.LiveVoiceCapture
import com.bitchat.android.features.video.LiveVideoCapture
import com.bitchat.android.features.video.LiveVideoEvent
import com.bitchat.android.features.video.LiveVideoLimits
import com.bitchat.android.features.video.LiveVideoManager
import com.bitchat.android.features.video.LiveVideoQuality
import com.bitchat.android.features.video.LiveVideoTarget
import com.bitchat.android.features.video.VideoTranscoder
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.mesh.TransferProgressManager
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.PrivateMediaRecipientResolver
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Headless engine behind [TestHookReceiver]. Drives the public [MeshService] API and
 * observes state via [AppStateStore] flows (never touches the single-slot mesh delegate).
 */
object TestHookDriver {

    private const val TAG = TestHookReceiver.TAG

    private const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L
    private const val DEFAULT_RECV_TIMEOUT_MS = 60_000L
    private const val DEFAULT_FILE_TIMEOUT_MS = 180_000L

    suspend fun execute(context: Context, cmd: String, intent: Intent): JSONObject {
        Log.d(TAG, "execute cmd=$cmd")
        val result = when (cmd) {
            "ping" -> ok(cmd).put("pong", true).put("package", context.packageName)
            "start" -> start(context)
            "stop" -> stop(context)
            "whoami" -> whoami(context)
            "set_nickname" -> setNickname(context, intent.requiredString("name"))
            "scan" -> scan(context, intent)
            "peers" -> peers(context)
            "connect" -> connect(intent.requiredString("peer"), intent)
            "handshake" -> handshake(context, intent.requiredString("peer"), intent)
            "session" -> session(context, intent.requiredString("peer"))
            "announce" -> announce(context)
            "broadcast_msg" -> broadcastMsg(context, intent.requiredString("content"), intent.getStringExtra("channel"))
            "dm_send" -> dmSend(context, intent.requiredString("peer"), intent.requiredString("content"), intent.getStringExtra("msg_id"))
            "dm_recv" -> dmRecv(context, intent)
            "msg_recv" -> msgRecv(context, intent)
            "favorite_set" -> favoriteSet(
                context,
                intent.requiredString("peer"),
                intent.getBooleanExtra("enabled", true)
            )
            "favorite_status" -> favoriteStatus(context, intent.requiredString("peer"))
            "verification_set" -> verificationSet(
                context,
                intent.requiredString("peer"),
                intent.getBooleanExtra("enabled", true)
            )
            "verification_status" -> verificationStatus(context, intent.requiredString("peer"))
            "file_send" -> fileSend(context, intent)
            "file_recv" -> fileRecv(context, intent)
            "file_cancel" -> fileCancel(context, intent.requiredString("transfer_id"))
            "ptt_send" -> pttSend(context, intent)
            "ptt_recv" -> pttRecv(context, intent)
            "video_send" -> videoSend(context, intent)
            "video_recv" -> videoRecv(context, intent)
            "video_file_send" -> videoFileSend(context, intent)
            "raw_send" -> rawSend(context, intent)
            "ble" -> setBle(intent.getBooleanExtra("enabled", true))
            "inject_peers" -> injectPeers(intent.getStringExtra("peers"))
            "state" -> state(context)
            "clear_results" -> clearResults(context)
            else -> err(cmd, "unknown command: $cmd")
        }
        return result.put("cmd", cmd)
    }

    // MARK: - Lifecycle

    private fun start(context: Context): JSONObject {
        MeshForegroundService.start(context)
        val mesh = mesh(context)
        mesh.startServices()
        return ok("start").put("peer_id", mesh.myPeerID)
    }

    private fun stop(context: Context): JSONObject {
        try {
            MeshServiceHolder.unifiedMeshService?.stopServices()
        } catch (e: Exception) {
            Log.w(TAG, "stopServices failed: ${e.message}")
        }
        MeshForegroundService.stop(context)
        return ok("stop")
    }

    // MARK: - Identity

    private fun whoami(context: Context): JSONObject {
        val mesh = mesh(context)
        return ok("whoami")
            .put("peer_id", mesh.myPeerID)
            .put("identity_fingerprint", mesh.getIdentityFingerprint())
            .put("noise_public_key", mesh.getStaticNoisePublicKey()?.toHex())
            .put("nickname", AppStateStore.nickname.value)
    }

    private fun setNickname(context: Context, name: String): JSONObject {
        DataManager(context).saveNickname(name)
        AppStateStore.setNickname(name)
        mesh(context).sendBroadcastAnnounce()
        return ok("set_nickname").put("nickname", name)
    }

    // MARK: - Discovery / connection

    private suspend fun scan(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_SCAN_TIMEOUT_MS)
        val minPeers = intent.getIntExtra("min_peers", 1)
        val mesh = mesh(context)
        val found = withTimeoutOrNull(timeoutMs) {
            AppStateStore.peers.first { it.size >= minPeers }
        }
        val peerIds = found ?: AppStateStore.peers.value
        return ok("scan")
            .put("reached_min_peers", found != null)
            .put("peers", peerInfosJson(mesh, peerIds))
    }

    private fun peers(context: Context): JSONObject {
        val mesh = mesh(context)
        return ok("peers").put("peers", peerInfosJson(mesh, AppStateStore.peers.value))
    }

    /**
     * Debug-only state injection for testing peer-list consumers such as notifications.
     * A blank or missing comma-separated value restores the empty state.
     */
    private fun injectPeers(commaSeparatedPeers: String?): JSONObject {
        val peers = commaSeparatedPeers
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        AppStateStore.setPeers(peers)
        return ok("inject_peers").put("peers", JSONArray(peers))
    }

    private suspend fun connect(peerID: String, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_CONNECT_TIMEOUT_MS)
        val ble = MeshServiceHolder.meshService ?: return err("connect", "BLE service not running")
        val address = ble.getDeviceAddressForPeer(peerID)
            ?: return err("connect", "no device address known for peer $peerID (scan first)")
        val accepted = ble.connectionManager.connectToAddress(address)
        if (!accepted) return err("connect", "connectToAddress($address) rejected")
        val direct = withTimeoutOrNull(timeoutMs) {
            AppStateStore.directPeers.first { it.contains(peerID) }
        }
        return ok("connect")
            .put("peer", peerID)
            .put("address", address)
            .put("direct", direct != null)
    }

    // MARK: - Noise

    private suspend fun handshake(context: Context, peerID: String, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_HANDSHAKE_TIMEOUT_MS)
        val mesh = mesh(context)
        val deadline = System.currentTimeMillis() + timeoutMs
        if (!mesh.hasEstablishedSession(peerID)) {
            mesh.initiateNoiseHandshake(peerID)
        }
        var lastState: NoiseSession.NoiseSessionState = NoiseSession.NoiseSessionState.Uninitialized
        while (System.currentTimeMillis() < deadline) {
            lastState = mesh.getSessionState(peerID)
            when (lastState) {
                is NoiseSession.NoiseSessionState.Established -> {
                    return ok("handshake")
                        .put("peer", peerID)
                        .put("state", lastState.toString())
                        .put("fingerprint", mesh.getPeerFingerprint(peerID))
                }
                is NoiseSession.NoiseSessionState.Failed -> {
                    return err("handshake", "session failed: $lastState").put("peer", peerID)
                }
                else -> delay(100)
            }
        }
        return err("handshake", "timeout after ${timeoutMs}ms (last state: $lastState)").put("peer", peerID)
    }

    private fun session(context: Context, peerID: String): JSONObject {
        val mesh = mesh(context)
        return ok("session")
            .put("peer", peerID)
            .put("state", mesh.getSessionState(peerID).toString())
            .put("established", mesh.hasEstablishedSession(peerID))
            .put("fingerprint", mesh.getPeerFingerprint(peerID))
    }

    // MARK: - Messaging

    private fun announce(context: Context): JSONObject {
        mesh(context).sendBroadcastAnnounce()
        return ok("announce")
    }

    private fun broadcastMsg(context: Context, content: String, channel: String?): JSONObject {
        mesh(context).sendMessage(content, emptyList(), channel)
        return ok("broadcast_msg").put("content", content).put("channel", channel)
    }

    private fun dmSend(context: Context, peerID: String, content: String, msgID: String?): JSONObject {
        val mesh = mesh(context)
        val nickname = mesh.getPeerNicknames()[peerID] ?: peerID
        val id = msgID ?: "testhook-${System.currentTimeMillis()}"
        mesh.sendPrivateMessage(content, peerID, nickname, id)
        return ok("dm_send").put("peer", peerID).put("msg_id", id)
    }

    private suspend fun dmRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_RECV_TIMEOUT_MS)
        val fromPeer = intent.getStringExtra("peer")
        val contains = intent.getStringExtra("contains")
        val mesh = mesh(context)
        val matcher = ArrivedMessageMatcher(AppStateStore.privateMessages.value.values.flatten()) { msg ->
            msg.senderPeerID != mesh.myPeerID &&
                (fromPeer == null || msg.senderPeerID == fromPeer) &&
                (contains == null || msg.content.contains(contains))
        }
        val msg = withTimeoutOrNull(timeoutMs) {
            AppStateStore.privateMessages
                .mapNotNull { conversations -> matcher.firstArrived(conversations.values.flatten()) }
                .first()
        } ?: return err("dm_recv", "timeout after ${timeoutMs}ms")
        return ok("dm_recv")
            .put("from", msg.senderPeerID)
            .put("sender", msg.sender)
            .put("content", msg.content)
            .put("msg_id", msg.id)
    }

    private suspend fun msgRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_RECV_TIMEOUT_MS)
        val contains = intent.getStringExtra("contains")
        val channel = intent.getStringExtra("channel")
        val mesh = mesh(context)
        val accepts: (com.bitchat.android.model.BitchatMessage) -> Boolean = { msg ->
            msg.senderPeerID != mesh.myPeerID &&
                (contains == null || msg.content.contains(contains)) &&
                (channel == null || msg.channel == channel)
        }
        val found = withTimeoutOrNull(timeoutMs) {
            if (channel != null) {
                val matcher = ArrivedMessageMatcher(AppStateStore.channelMessages.value.values.flatten(), accepts)
                AppStateStore.channelMessages.mapNotNull { m -> matcher.firstArrived(m.values.flatten()) }.first()
            } else {
                val matcher = ArrivedMessageMatcher(AppStateStore.publicMessages.value, accepts)
                AppStateStore.publicMessages.mapNotNull(matcher::firstArrived).first()
            }
        } ?: return err("msg_recv", "timeout after ${timeoutMs}ms")
        return ok("msg_recv")
            .put("from", found.senderPeerID)
            .put("sender", found.sender)
            .put("content", found.content)
            .put("channel", found.channel)
            .put("msg_id", found.id)
    }

    // MARK: - Favorite and verification state

    private fun favoriteSet(context: Context, peerID: String, enabled: Boolean): JSONObject {
        val mesh = mesh(context)
        val peerInfo = mesh.getPeerInfo(peerID)
            ?: return err("favorite_set", "peer is not known")
        val noisePublicKey = peerInfo.noisePublicKey
            ?: return err("favorite_set", "peer Noise key is unavailable")
        FavoritesPersistenceService.initialize(context)
        FavoritesPersistenceService.shared.updateFavoriteStatus(
            noisePublicKey = noisePublicKey,
            nickname = peerInfo.nickname,
            isFavorite = enabled
        )
        mesh.sendFavoriteNotification(peerID, enabled)
        return favoriteStatus(context, peerID)
    }

    private fun favoriteStatus(context: Context, peerID: String): JSONObject {
        val mesh = mesh(context)
        FavoritesPersistenceService.initialize(context)
        val relationship = FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
            ?: mesh.getPeerInfo(peerID)?.noisePublicKey?.let {
                FavoritesPersistenceService.shared.getFavoriteStatus(it)
            }
        val isFavorite = relationship?.isFavorite == true
        val theyFavoritedUs = relationship?.theyFavoritedUs == true
        return ok("favorite_status")
            .put("peer", peerID)
            .put("is_favorite", isFavorite)
            .put("they_favorited_us", theyFavoritedUs)
            .put("is_mutual", isFavorite && theyFavoritedUs)
            .put(
                "star_state",
                when {
                    isFavorite -> "filled"
                    theyFavoritedUs -> "outlined_orange"
                    else -> "outlined"
                }
            )
    }

    private fun verificationSet(context: Context, peerID: String, enabled: Boolean): JSONObject {
        val mesh = mesh(context)
        val fingerprint = mesh.getPeerFingerprint(peerID)
            ?: return err("verification_set", "peer fingerprint is unavailable")
        SecureIdentityStateManager(context).setVerifiedFingerprint(fingerprint, enabled)
        return verificationStatus(context, peerID)
    }

    private fun verificationStatus(context: Context, peerID: String): JSONObject {
        val fingerprint = mesh(context).getPeerFingerprint(peerID)
        val verified = fingerprint != null &&
            SecureIdentityStateManager(context).getVerifiedFingerprints().any {
                it.equals(fingerprint, ignoreCase = true)
            }
        return ok("verification_status")
            .put("peer", peerID)
            .put("fingerprint", fingerprint ?: JSONObject.NULL)
            .put("verified", verified)
    }

    // MARK: - File transfer

    private suspend fun fileSend(context: Context, intent: Intent): JSONObject {
        val path = intent.requiredString("path")
        val peerID = intent.getStringExtra("peer")
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_FILE_TIMEOUT_MS)
        val mesh = mesh(context)

        val file = File(path)
        if (!file.isFile) return err("file_send", "file not found: $path")
        val content = withContext(Dispatchers.IO) { file.readBytes() }
        if (content.size.toLong() > AppConstants.Media.MAX_FILE_SIZE_BYTES) {
            return err("file_send", "file too large: ${content.size} > ${AppConstants.Media.MAX_FILE_SIZE_BYTES}")
        }
        val packet = BitchatFilePacket(
            fileName = file.name,
            fileSize = content.size.toLong(),
            mimeType = intent.getStringExtra("mime") ?: FileUtils.getMimeTypeFromExtension(file.name),
            content = content
        )
        val encoded = packet.encode() ?: return err("file_send", "failed to TLV-encode packet")
        val transferId = sha256Hex(encoded)
        val recipient = peerID?.let {
            PrivateMediaRecipientResolver.resolve(it, mesh)
                ?: return err("file_send", "no active mesh route for private conversation: $it")
        }

        return coroutineScope {
            // Subscribe on a background dispatcher before sending so synchronous
            // failure events are not missed (SharedFlow has replay=0).
            val completion = async(Dispatchers.Default) {
                TransferProgressManager.events.first { it.transferId == transferId && it.completed }
            }
            delay(50)
            val sendError = dispatchFileSend(
                context,
                intent,
                mesh,
                recipient?.meshPeerID,
                packet,
                transferId
            )
            if (sendError != null) {
                completion.cancel()
                return@coroutineScope sendError.put("cmd", "file_send")
            }
            val event = withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: return@coroutineScope err("file_send", "timeout waiting for transfer completion ($transferId)")
            if (event.failed) {
                return@coroutineScope err("file_send", "transfer rejected/failed before send ($transferId)")
                    .put("transfer_id", transferId)
            }
            ok("file_send")
                .put("transfer_id", transferId)
                .put("sent", event.sent)
                .put("total", event.total)
                .put("bytes", content.size)
                .put("peer", recipient?.meshPeerID)
                .put("conversation", peerID)
        }
    }

    private suspend fun dispatchFileSend(
        context: Context,
        intent: Intent,
        mesh: MeshService,
        peerID: String?,
        packet: BitchatFilePacket,
        transferId: String
    ): JSONObject? {
        if (peerID == null) {
            mesh.sendFileBroadcast(packet)
            return null
        }
        if (!mesh.hasEstablishedSession(peerID)) {
            val hs = handshake(context, peerID, intent)
            if (hs.optString("status") != "ok") return hs
        }
        // Peer state (capabilities/identity) can lag session establishment;
        // retry transient preparation states before giving up.
        val prepDeadline = System.currentTimeMillis() + 30_000
        while (true) {
            when (val prep = mesh.prepareFilePrivate(peerID, packet, transferId, allowLegacyFallback = false)) {
                is PrivateMediaPreparation.Ready -> {
                    return if (prep.transfer.commit()) null else err("file_send", "private transfer commit failed")
                }
                PrivateMediaPreparation.AwaitingPeerState,
                PrivateMediaPreparation.NeedsHandshake -> {
                    if (System.currentTimeMillis() >= prepDeadline) {
                        return err("file_send", "private media preparation stuck at: $prep")
                    }
                    if (prep == PrivateMediaPreparation.NeedsHandshake) {
                        mesh.initiateNoiseHandshake(peerID)
                    }
                    delay(500)
                }
                else -> return err("file_send", "private media preparation: $prep")
            }
        }
    }

    private suspend fun fileRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_FILE_TIMEOUT_MS)
        val nameContains = intent.getStringExtra("name_contains")
        val startTime = System.currentTimeMillis()
        val dirs = listOf(
            File(context.cacheDir, "files/incoming"),
            File(context.cacheDir, "images/incoming")
        )
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val candidate = dirs
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.lastModified() >= startTime - 5_000 }
                .filter { nameContains == null || it.name.contains(nameContains) }
                .maxByOrNull { it.lastModified() }
            if (candidate != null) {
                val size1 = candidate.length()
                delay(500)
                if (candidate.length() == size1 && size1 > 0) {
                    return ok("file_recv")
                        .put("path", candidate.absolutePath)
                        .put("name", candidate.name)
                        .put("bytes", size1)
                        .put("sha256", withContext(Dispatchers.IO) { sha256Hex(candidate.readBytes()) })
                }
            }
            delay(250)
        }
        return err("file_recv", "timeout after ${timeoutMs}ms")
    }

    private fun fileCancel(context: Context, transferId: String): JSONObject {
        val cancelled = mesh(context).cancelFileTransfer(transferId)
        return ok("file_cancel").put("transfer_id", transferId).put("cancelled", cancelled)
    }

    // MARK: - Live push-to-talk

    private suspend fun pttSend(context: Context, intent: Intent): JSONObject {
        val requestedPeer = intent.getStringExtra("peer")
        val durationMs = intent.getIntExtra("duration_ms", 1_500).toLong().coerceIn(700L, 10_000L)
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_FILE_TIMEOUT_MS)
        val mesh = mesh(context)
        LiveVoicePreferences.setEnabled(context, true)
        val recipient = requestedPeer?.let {
            PrivateMediaRecipientResolver.resolve(it, mesh)
                ?: return err("ptt_send", "no active mesh route for private conversation")
        }
        if (recipient != null && !mesh.hasEstablishedSession(recipient.meshPeerID)) {
            val handshake = handshake(context, recipient.meshPeerID, intent)
            if (handshake.optString("status") != "ok") return handshake.put("cmd", "ptt_send")
        }
        val target = LiveVoiceTarget { payload -> mesh.sendVoiceFrame(recipient?.meshPeerID, payload) }
        val recorder = LiveVoiceCapture(
            File(context.filesDir, "voicenotes/outgoing"),
            target,
            syntheticPcm = true
        )
        val pendingFile = recorder.start() ?: return err("ptt_send", "live codec failed to start")
        delay(durationMs)
        val finalFile = recorder.stop(canceled = false)
            ?: return err("ptt_send", "capture did not produce a finalized note")
        val captureStats = recorder.stats()
        if (finalFile != pendingFile || !finalFile.isFile) {
            return err("ptt_send", "finalized note is unavailable")
        }
        val content = withContext(Dispatchers.IO) { finalFile.readBytes() }
        val packet = BitchatFilePacket(
            fileName = finalFile.name,
            fileSize = content.size.toLong(),
            mimeType = "audio/mp4",
            content = content
        )
        val encoded = packet.encode() ?: return err("ptt_send", "failed to encode finalized note")
        val transferId = sha256Hex(encoded)
        return coroutineScope {
            val completion = async(Dispatchers.Default) {
                TransferProgressManager.events.first { it.transferId == transferId && it.completed }
            }
            delay(50)
            val sendError = dispatchFileSend(
                context,
                intent,
                mesh,
                recipient?.meshPeerID,
                packet,
                transferId
            )
            if (sendError != null) {
                completion.cancel()
                return@coroutineScope sendError.put("cmd", "ptt_send")
            }
            val event = withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: return@coroutineScope err("ptt_send", "timeout waiting for finalized note transfer")
            if (event.failed) return@coroutineScope err("ptt_send", "finalized note transfer failed")
            ok("ptt_send")
                .put("live", true)
                .put("scope", if (recipient == null) "public" else "dm")
                .put("duration_ms", durationMs)
                .put("burst_id", LiveVoiceManager.burstIDFromVoiceFileName(finalFile.name))
                .put("bytes", content.size)
                .put("queued_pcm_frames", captureStats.queuedPcmFrames)
                .put("encoded_frames", captureStats.encodedFrames)
                .put("data_packets", captureStats.dataPackets)
                .put("dropped_oversize_frames", captureStats.droppedOversizeFrames)
                .put("outbound_packets", captureStats.outboundPackets)
                .put("delivered_packets", captureStats.deliveredPackets)
        }
    }

    private suspend fun pttRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_FILE_TIMEOUT_MS)
        val fromPeer = intent.getStringExtra("peer")
        val expectedScope = if (intent.getStringExtra("scope") == "public") {
            LiveVoiceScope.PUBLIC_MESH
        } else {
            LiveVoiceScope.DIRECT_MESSAGE
        }
        LiveVoicePreferences.setEnabled(context, true)
        var finished: LiveVoiceEvent.Finished? = null
        var liveSnapshot: File? = null
        val absorbed = withTimeoutOrNull(timeoutMs) {
            LiveVoiceManager.getInstance(context).events.first { event ->
                val matches = event.scope == expectedScope &&
                    (fromPeer == null || event.peerID == fromPeer)
                if (matches && event is LiveVoiceEvent.Finished) {
                    finished = event
                    liveSnapshot = runCatching {
                        File(context.cacheDir, "testhook/ptt-${event.burstID}.aac").also { snapshot ->
                            snapshot.parentFile?.mkdirs()
                            File(event.path).copyTo(snapshot, overwrite = true)
                        }
                    }.getOrNull()
                }
                matches && event is LiveVoiceEvent.Absorbed
            } as LiveVoiceEvent.Absorbed
        } ?: return err("ptt_recv", "timeout waiting for live burst and finalized note")
        val analysis = liveSnapshot?.let { snapshot ->
            try {
                withContext(Dispatchers.IO) { PttTestAudioAnalyzer.analyze(snapshot) }
            } finally {
                snapshot.delete()
            }
        } ?: return err("ptt_recv", "live AAC snapshot was unavailable")
        return ok("ptt_recv")
            .put("live_observed", finished != null)
            .put("scope", if (expectedScope == LiveVoiceScope.PUBLIC_MESH) "public" else "dm")
            .put("from", absorbed.peerID)
            .put("burst_id", absorbed.burstID)
            .put("frames", finished?.frames ?: 0)
            .put("data_packets", finished?.dataPackets ?: 0)
            .put("bytes", finished?.bytes ?: 0)
            .put("expected_packets", finished?.expectedPackets ?: 0)
            .put("missing_packets", finished?.missingPackets ?: 0)
            .put("decoded_samples", analysis.decodedSamples)
            .put("rms", analysis.rms)
            .put("silent_block_fraction", analysis.silentBlockFraction)
            .put("longest_silent_block_run", analysis.longestSilentBlockRun)
            .put("zero_crossings_per_second", analysis.zeroCrossingsPerSecond)
    }

    // MARK: - Live video

    /**
     * Streams a synthetic test card (and tone) live to [peer] or the public mesh, then sends the
     * finalized MP4 when it fits one file transfer. Never touches the real camera or microphone.
     */
    private suspend fun videoSend(context: Context, intent: Intent): JSONObject {
        val requestedPeer = intent.getStringExtra("peer")
        val durationMs = intent.getIntExtra("duration_ms", 8_000).toLong().coerceIn(1_500L, 120_000L)
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_FILE_TIMEOUT_MS)
        val quality = LiveVideoQuality.fromName(intent.getStringExtra("quality"))
        val withAudio = intent.getBooleanExtra("audio", true)
        val sendFinal = intent.getBooleanExtra("send_final", true)
        val mesh = mesh(context)
        val recipient = requestedPeer?.let {
            PrivateMediaRecipientResolver.resolve(it, mesh)
                ?: return err("video_send", "no active mesh route for private conversation")
        }
        if (recipient != null && !mesh.hasEstablishedSession(recipient.meshPeerID)) {
            val handshake = handshake(context, recipient.meshPeerID, intent)
            if (handshake.optString("status") != "ok") return handshake.put("cmd", "video_send")
        }
        // Simulated mesh loss: independent drops (`drop_rate`) plus one burst
        // (`burst_start`, `burst_length`), applied before packets reach the radio.
        val dropRate = intent.getFloatExtra("drop_rate", 0f).coerceIn(0f, 0.9f)
        val burstStart = intent.getIntExtra("burst_start", -1)
        val burstLength = intent.getIntExtra("burst_length", 0)
        // Simulated link outage: nothing reaches the radio for `outage_ms` from `outage_start_ms`.
        val outageStartMs = intent.getLongExtra("outage_start_ms", -1L)
        val outageMs = intent.getLongExtra("outage_ms", 0L)
        val streamStart = System.currentTimeMillis()
        val random = java.util.Random(intent.getLongExtra("seed", 1L))
        val packetIndex = java.util.concurrent.atomic.AtomicInteger()
        val dropped = java.util.concurrent.atomic.AtomicInteger()
        val target = LiveVideoTarget.of({ payload ->
            val index = packetIndex.getAndIncrement()
            val inBurst = burstStart >= 0 && index in burstStart until burstStart + burstLength
            val sinceStart = System.currentTimeMillis() - streamStart
            val inOutage = outageStartMs >= 0 && sinceStart in outageStartMs until outageStartMs + outageMs
            if (inBurst || inOutage || random.nextFloat() < dropRate) {
                dropped.incrementAndGet()
            } else {
                mesh.sendVideoFrame(recipient?.meshPeerID, payload)
            }
        }, mesh::liveSendBacklog, { mesh.liveSendPacketBudget(recipient?.meshPeerID) })
        val capture = LiveVideoCapture(
            File(context.filesDir, "videos/outgoing"),
            target,
            quality,
            audioEnabled = withAudio,
            synthetic = true
        )
        capture.start() ?: return err("video_send", "video codec failed to start")
        delay(durationMs)
        val finalFile = withContext(Dispatchers.IO) { capture.stop(canceled = false) }
            ?: return err("video_send", "capture did not produce a finalized video")
        val stats = capture.stats()
        val result = ok("video_send")
            .put("scope", if (recipient == null) "public" else "dm")
            .put("quality", quality.name)
            .put("duration_ms", durationMs)
            .put("stream_id", LiveVideoManager.streamIDFromVideoFileName(finalFile.name))
            .put("file_bytes", finalFile.length())
            .put("frames_offered", stats.framesOffered)
            .put("frames_encoded", stats.framesEncoded)
            .put("frames_dropped", stats.framesDropped)
            .put("video_units", stats.videoUnits)
            .put("audio_units", stats.audioUnits)
            .put("packets_sent", stats.packetsSent)
            .put("parity_packets", stats.parityPackets)
            .put("bytes_sent", stats.bytesSent)
            .put("send_kbps", stats.sendKbps)
            .put("simulated_drops", dropped.get())
            .put("mean_encode_ms", stats.meanEncodeMs)
            .put("final_video_bitrate", stats.videoBitrate)
            .put("congestion_skipped_units", stats.congestionSkippedUnits)
            .put("congestion_episodes", stats.congestionEpisodes)
            .put("max_backlog", stats.maxBacklog)
            .put("stale_dropped", stats.staleDropped)
        if (!sendFinal || finalFile.length() > LiveVideoLimits.MAX_VIDEO_FILE_BYTES) {
            return result.put("final_sent", false)
        }
        val transfer = sendVideoFile(context, intent, mesh, recipient?.meshPeerID, finalFile, timeoutMs)
        return if (transfer != null) transfer.put("cmd", "video_send") else result.put("final_sent", true)
    }

    /** Waits for a live stream to finish (and, by default, for its final file to replace it). */
    private suspend fun videoRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_FILE_TIMEOUT_MS)
        val fromPeer = intent.getStringExtra("peer")
        val expectPrivate = intent.getStringExtra("scope") == "dm"
        val expectFinal = intent.getBooleanExtra("expect_final", true)
        val manager = LiveVideoManager.getInstance(context)
        val reconnectWindowMs = intent.getLongExtra("reconnect_window_ms", -1L)
        val previousWindow = manager.reconnectWindowMs
        if (reconnectWindowMs > 0) manager.reconnectWindowMs = reconnectWindowMs
        var finished: LiveVideoEvent.Finished? = null
        var playable: JSONObject? = null
        var sessions = 0
        var silenceCloses = 0
        val terminal = try {
            withTimeoutOrNull(timeoutMs) {
                manager.events.first { event ->
                    val matches = event.isPrivate == expectPrivate && (fromPeer == null || event.peerID == fromPeer)
                    if (matches && event is LiveVideoEvent.Started) sessions++
                    if (matches && event is LiveVideoEvent.Finished) {
                        finished = event
                        if (!event.endedBySender) silenceCloses++
                        if (event.endedBySender) playable = event.path?.let { probeVideo(it) }
                    }
                    matches && (event is LiveVideoEvent.Canceled ||
                        (event is LiveVideoEvent.Absorbed) ||
                        (!expectFinal && event is LiveVideoEvent.Finished && event.endedBySender))
                }
            }
        } finally {
            manager.reconnectWindowMs = previousWindow
        } ?: return err("video_recv", "timeout waiting for live video")
            .put("live_observed", finished != null)
            .put("sessions", sessions)
            .put("silence_closes", silenceCloses)
        if (terminal is LiveVideoEvent.Canceled) return err("video_recv", "stream canceled")
        val done = finished ?: return err("video_recv", "final file arrived without a live stream")
        val result = ok("video_recv")
            .put("scope", if (expectPrivate) "dm" else "public")
            .put("from", done.peerID)
            .put("stream_id", done.streamID)
            .put("packets", done.packets)
            .put("bytes", done.bytes)
            .put("recovered_shards", done.recoveredShards)
            .put("video_units", done.videoUnits)
            .put("audio_units", done.audioUnits)
            .put("skipped_video_units", done.skippedVideoUnits)
            .put("decoded_frames", done.decodedFrames)
            .put("mean_luma", done.meanLuma)
            .put("mean_latency_ms", done.meanLatencyMs)
            .put("max_latency_ms", done.maxLatencyMs)
            .put("sessions", sessions)
            .put("silence_closes", silenceCloses)
            .put("reconstruction", playable ?: JSONObject().put("playable", false))
        if (terminal is LiveVideoEvent.Absorbed) {
            result.put("final_absorbed", true).put("final", probeVideo(terminal.finalizedPath))
        }
        return result
    }

    /**
     * Records a synthetic clip (or, with `transcode_from_quality`, a larger stream that is then
     * re-encoded to fit) and sends it as an ordinary video message.
     */
    private suspend fun videoFileSend(context: Context, intent: Intent): JSONObject {
        val requestedPeer = intent.getStringExtra("peer")
        val durationMs = intent.getIntExtra("duration_ms", 6_000).toLong().coerceIn(1_500L, 60_000L)
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_FILE_TIMEOUT_MS)
        val sourceQuality = LiveVideoQuality.fromName(intent.getStringExtra("transcode_from_quality") ?: "clip")
        val mesh = mesh(context)
        val recipient = requestedPeer?.let {
            PrivateMediaRecipientResolver.resolve(it, mesh)
                ?: return err("video_file_send", "no active mesh route for private conversation")
        }
        val outgoing = File(context.filesDir, "videos/outgoing")
        val capture = LiveVideoCapture(outgoing, null, sourceQuality, audioEnabled = true, synthetic = true)
        capture.start() ?: return err("video_file_send", "video codec failed to start")
        delay(durationMs)
        val recorded = withContext(Dispatchers.IO) { capture.stop(canceled = false) }
            ?: return err("video_file_send", "capture did not produce a video")
        val recordedBytes = recorded.length()
        val transcodeStarted = System.currentTimeMillis()
        val ready = if (recordedBytes <= LiveVideoLimits.MAX_VIDEO_FILE_BYTES) recorded else {
            withContext(Dispatchers.IO) {
                VideoTranscoder.transcode(
                    context,
                    android.net.Uri.fromFile(recorded),
                    File(outgoing, "clip_transcoded_${System.currentTimeMillis()}.mp4")
                )?.file
            } ?: return err("video_file_send", "transcode failed").put("recorded_bytes", recordedBytes)
        }
        val transcodeMs = System.currentTimeMillis() - transcodeStarted
        val sent = sendVideoFile(context, intent, mesh, recipient?.meshPeerID, ready, timeoutMs)
        if (sent != null) return sent.put("cmd", "video_file_send")
        return ok("video_file_send")
            .put("recorded_bytes", recordedBytes)
            .put("sent_bytes", ready.length())
            .put("transcoded", ready != recorded)
            .put("transcode_ms", transcodeMs)
            .put("file_name", ready.name)
            .put("video", probeVideo(ready.absolutePath))
    }

    /** Sends a video file and waits for transfer completion; returns an error object or null. */
    private suspend fun sendVideoFile(
        context: Context,
        intent: Intent,
        mesh: MeshService,
        peerID: String?,
        file: File,
        timeoutMs: Long
    ): JSONObject? {
        val content = withContext(Dispatchers.IO) { file.readBytes() }
        val packet = BitchatFilePacket(
            fileName = file.name,
            fileSize = content.size.toLong(),
            mimeType = "video/mp4",
            content = content
        )
        val encoded = packet.encode() ?: return err("video_send", "failed to encode video file")
        val transferId = sha256Hex(encoded)
        return coroutineScope {
            val completion = async(Dispatchers.Default) {
                TransferProgressManager.events.first { it.transferId == transferId && it.completed }
            }
            delay(50)
            val sendError = dispatchFileSend(context, intent, mesh, peerID, packet, transferId)
            if (sendError != null) {
                completion.cancel()
                return@coroutineScope sendError
            }
            val event = withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: return@coroutineScope err("video_send", "timeout waiting for video file transfer")
            if (event.failed) err("video_send", "video file transfer failed") else null
        }
    }

    private fun probeVideo(path: String): JSONObject {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(0)
            JSONObject()
                .put("playable", frame != null)
                .put("bytes", File(path).length())
                .put("duration_ms", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
                .put("width", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                .put("height", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                .put("has_audio", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } catch (error: Exception) {
            JSONObject().put("playable", false).put("error", error.message)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // MARK: - Raw packet injection

    private fun rawSend(context: Context, intent: Intent): JSONObject {
        val payloadHex = intent.requiredString("payload_hex")
        val typeStr = intent.requiredString("type")
        val peerID = intent.getStringExtra("peer")
        val ttl = intent.getIntExtra("ttl", 7)
        val type = typeStr.toUIntOrNull(16)?.toUByte()
            ?: return err("raw_send", "invalid type hex: $typeStr")
        val payload = hexToBytes(payloadHex)
            ?: return err("raw_send", "invalid payload_hex")
        val mesh = mesh(context)
        val packet = BitchatPacket(
            type = type,
            ttl = ttl.toUByte(),
            senderID = mesh.myPeerID,
            payload = payload
        )
        if (peerID != null) {
            TransportBridgeService.sendToPeerFromLocal(peerID, packet)
        } else {
            TransportBridgeService.broadcastFromLocal(RoutedPacket(packet))
        }
        return ok("raw_send")
            .put("type", typeStr)
            .put("payload_bytes", payload.size)
            .put("peer", peerID)
    }

    // MARK: - Transport / state

    private fun setBle(enabled: Boolean): JSONObject {
        val ble = MeshServiceHolder.meshService ?: return err("ble", "BLE service not running")
        ble.setBleTransportEnabled(enabled)
        return ok("ble").put("enabled", enabled)
    }

    private fun state(context: Context): JSONObject {
        val mesh = mesh(context)
        val peersJson = peerInfosJson(mesh, AppStateStore.peers.value)
        val sessions = JSONObject()
        AppStateStore.peers.value.forEach { peerID ->
            sessions.put(peerID, mesh.getSessionState(peerID).toString())
        }
        return ok("state")
            .put("peer_id", mesh.myPeerID)
            .put("nickname", AppStateStore.nickname.value)
            .put("peers", peersJson)
            .put("direct_peers", JSONArray(AppStateStore.directPeers.value.toList()))
            .put("sessions", sessions)
            .put("device_map", JSONObject(mesh.getDeviceAddressToPeerMapping() as Map<*, *>))
            .put("debug_status", mesh.getDebugStatus())
    }

    private fun clearResults(context: Context): JSONObject {
        val dir = File(context.cacheDir, "testhook/results")
        val count = dir.listFiles()?.count { it.delete() } ?: 0
        return ok("clear_results").put("deleted", count)
    }

    // MARK: - Helpers

    private fun mesh(context: Context): MeshService = MeshServiceHolder.getUnifiedOrCreate(context)

    private fun peerInfosJson(mesh: MeshService, peerIds: List<String>): JSONArray {
        val nicknames = mesh.getPeerNicknames()
        val rssi = mesh.getPeerRSSI()
        val arr = JSONArray()
        peerIds.forEach { id ->
            val info = mesh.getPeerInfo(id)
            arr.put(JSONObject()
                .put("id", id)
                .put("nickname", nicknames[id] ?: info?.nickname)
                .put("rssi", rssi[id])
                .put("direct", AppStateStore.directPeers.value.contains(id))
                .put("connected", info?.isConnected)
                .put("last_seen", info?.lastSeen)
                .put("session", mesh.getSessionState(id).toString())
                .put("fingerprint", mesh.getPeerFingerprint(id)))
        }
        return arr
    }

    private fun ok(cmd: String) = JSONObject().put("status", "ok").put("cmd", cmd)
    private fun err(cmd: String, message: String) =
        JSONObject().put("status", "error").put("cmd", cmd).put("error", message)

    private fun Intent.requiredString(name: String): String =
        getStringExtra(name) ?: throw IllegalArgumentException("missing required extra: $name")

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.replace(" ", "")
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }
}
