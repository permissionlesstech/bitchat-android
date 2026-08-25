package com.bitchat.watch.testhook

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.features.voice.LiveVoiceEvent
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.LiveVoiceScope
import com.bitchat.android.features.voice.LiveVoiceTarget
import com.bitchat.android.features.voice.LiveVoiceCapture
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.service.WearMeshForegroundService
import com.bitchat.watch.ui.WearPeerIdentityState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Headless engine behind [WearTestHookReceiver]. Drives [WearMeshService] and observes
 * [AppStateStore] flows. Command set mirrors the phone's TestHookDriver (minus file transfer,
 * which is deferred on the watch).
 */
object WearTestHookDriver {

    private const val TAG = WearTestHookReceiver.TAG

    private const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L
    private const val DEFAULT_RECV_TIMEOUT_MS = 60_000L

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
            "broadcast_msg" -> broadcastMsg(context, intent.requiredString("content"))
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
            "raw_send" -> rawSend(context, intent)
            "file_recv" -> fileRecv(context, intent)
            "ptt_recv" -> pttRecv(context, intent)
            "ptt_send" -> pttSend(context, intent)
            "state" -> state(context)
            "clear_results" -> clearResults(context)
            else -> err(cmd, "unknown command: $cmd")
        }
        return result.put("cmd", cmd)
    }

    // MARK: - Lifecycle

    private fun start(context: Context): JSONObject {
        val mesh = mesh(context)
        try {
            context.startForegroundService(Intent(context, WearMeshForegroundService::class.java))
        } catch (e: Exception) {
            // Background FGS starts are restricted (API 31+); mesh_lab launches the app first,
            // but fall back to a service-less mesh start so the command still works.
            Log.w(TAG, "foreground service start failed, starting mesh directly: ${e.message}")
        }
        mesh.startServices()
        return ok("start").put("peer_id", mesh.myPeerID)
    }

    private fun stop(context: Context): JSONObject {
        try {
            WearMeshService.peek()?.stopServices()
        } catch (e: Exception) {
            Log.w(TAG, "stopServices failed: ${e.message}")
        }
        context.stopService(Intent(context, WearMeshForegroundService::class.java))
        return ok("stop")
    }

    // MARK: - Identity

    private fun whoami(context: Context): JSONObject {
        val mesh = mesh(context)
        return ok("whoami")
            .put("peer_id", mesh.myPeerID)
            .put("identity_fingerprint", mesh.getIdentityFingerprint())
            .put("noise_public_key", mesh.getStaticNoisePublicKey()?.toHex())
            .put("nickname", mesh.nickname)
    }

    private fun setNickname(context: Context, name: String): JSONObject {
        mesh(context).setNickname(name)
        AppStateStore.setNickname(name)
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

    private suspend fun connect(peerID: String, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_CONNECT_TIMEOUT_MS)
        val mesh = WearMeshService.peek() ?: return err("connect", "mesh service not running")
        // The address↔peer mapping is learned from direct-link announces and can lag
        // peer-list discovery (especially right after a restart); poll while announcing.
        val deadline = System.currentTimeMillis() + timeoutMs / 2
        var address: String? = mesh.getDeviceAddressForPeer(peerID)
        while (address == null && System.currentTimeMillis() < deadline) {
            mesh.sendBroadcastAnnounce()
            delay(1_000)
            address = mesh.getDeviceAddressForPeer(peerID)
        }
        if (address == null) {
            return err("connect", "no device address known for peer $peerID (scan first)")
        }
        val accepted = mesh.connectToPeer(peerID)
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

    private fun broadcastMsg(context: Context, content: String): JSONObject {
        mesh(context).sendChannelMessage(content, emptyList(), null)
        return ok("broadcast_msg").put("content", content)
    }

    private fun dmSend(context: Context, peerID: String, content: String, msgID: String?): JSONObject {
        val mesh = mesh(context)
        val nickname = mesh.getPeerNicknames()[peerID] ?: peerID
        val id = msgID ?: "testhook-${System.currentTimeMillis()}"
        mesh.sendPrivateMessageWithId(content, peerID, nickname, id)
        return ok("dm_send").put("peer", peerID).put("msg_id", id)
    }

    private suspend fun dmRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_RECV_TIMEOUT_MS)
        val fromPeer = intent.getStringExtra("peer")
        val contains = intent.getStringExtra("contains")
        val startTime = System.currentTimeMillis()
        val mesh = mesh(context)
        val match = withTimeoutOrNull(timeoutMs) {
            AppStateStore.privateMessages.first { conversations ->
                conversations.values.flatten().any { msg ->
                    msg.timestamp.time >= startTime &&
                        msg.senderPeerID != mesh.myPeerID &&
                        (fromPeer == null || msg.senderPeerID == fromPeer) &&
                        (contains == null || msg.content.contains(contains))
                }
            }
        } ?: return err("dm_recv", "timeout after ${timeoutMs}ms")
        val msg = match.values.flatten().first { msg ->
            msg.timestamp.time >= startTime &&
                msg.senderPeerID != mesh.myPeerID &&
                (fromPeer == null || msg.senderPeerID == fromPeer) &&
                (contains == null || msg.content.contains(contains))
        }
        return ok("dm_recv")
            .put("from", msg.senderPeerID)
            .put("sender", msg.sender)
            .put("content", msg.content)
            .put("msg_id", msg.id)
    }

    private suspend fun msgRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", DEFAULT_RECV_TIMEOUT_MS)
        val contains = intent.getStringExtra("contains")
        val startTime = System.currentTimeMillis()
        val mesh = mesh(context)
        val matches: (com.bitchat.android.model.BitchatMessage) -> Boolean = { msg ->
            msg.timestamp.time >= startTime &&
                msg.senderPeerID != mesh.myPeerID &&
                (contains == null || msg.content.contains(contains))
        }
        val found = withTimeoutOrNull(timeoutMs) {
            AppStateStore.publicMessages.first { l -> l.any(matches) }.first(matches)
        } ?: return err("msg_recv", "timeout after ${timeoutMs}ms")
        return ok("msg_recv")
            .put("from", found.senderPeerID)
            .put("sender", found.sender)
            .put("content", found.content)
            .put("msg_id", found.id)
    }

    // MARK: - Favorite and verification state

    private fun favoriteSet(context: Context, peerID: String, enabled: Boolean): JSONObject {
        val mesh = mesh(context)
        WearPeerIdentityState.initialize(context)
        if (!WearPeerIdentityState.setFavorite(peerID, enabled, mesh)) {
            return err("favorite_set", "peer Noise identity is unavailable")
        }
        return favoriteStatus(context, peerID)
    }

    private fun favoriteStatus(context: Context, peerID: String): JSONObject {
        val mesh = mesh(context)
        WearPeerIdentityState.initialize(context)
        val identity = WearPeerIdentityState.snapshot(peerID, mesh)
        return ok("favorite_status")
            .put("peer", peerID)
            .put("is_favorite", identity.isFavorite)
            .put("they_favorited_us", identity.theyFavoritedUs)
            .put("is_mutual", identity.isFavorite && identity.theyFavoritedUs)
            .put(
                "star_state",
                when (identity.favoriteIndicator) {
                    com.bitchat.watch.ui.FavoriteIndicator.Favorite -> "filled"
                    com.bitchat.watch.ui.FavoriteIndicator.FavoritedUs -> "outlined_orange"
                    com.bitchat.watch.ui.FavoriteIndicator.None -> "outlined"
                }
            )
    }

    private fun verificationSet(context: Context, peerID: String, enabled: Boolean): JSONObject {
        val mesh = mesh(context)
        WearPeerIdentityState.initialize(context)
        if (!WearPeerIdentityState.setVerified(peerID, enabled, mesh)) {
            return err("verification_set", "peer fingerprint is unavailable")
        }
        return verificationStatus(context, peerID)
    }

    private fun verificationStatus(context: Context, peerID: String): JSONObject {
        val mesh = mesh(context)
        WearPeerIdentityState.initialize(context)
        val identity = WearPeerIdentityState.snapshot(peerID, mesh)
        return ok("verification_status")
            .put("peer", peerID)
            .put("fingerprint", identity.fingerprint ?: JSONObject.NULL)
            .put("verified", identity.isVerified)
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

    // MARK: - File transfer (receive only; the watch does not send files via test hook)

    private suspend fun fileRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", 180_000L)
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
                        .put(
                            "sha256",
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(candidate.readBytes()).toHex()
                        )
                }
            }
            delay(250)
        }
        return err("file_recv", "timeout after ${timeoutMs}ms")
    }

    private suspend fun pttRecv(context: Context, intent: Intent): JSONObject {
        val timeoutMs = intent.getLongExtra("timeout_ms", 180_000L)
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
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PttTestAudioAnalyzer.analyze(snapshot)
                }
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

    private suspend fun pttSend(context: Context, intent: Intent): JSONObject {
        val peerID = intent.getStringExtra("peer")
        val durationMs = intent.getIntExtra("duration_ms", 1_500).toLong().coerceIn(700L, 10_000L)
        val mesh = mesh(context)
        LiveVoicePreferences.setEnabled(context, true)
        if (peerID != null && !mesh.hasEstablishedSession(peerID)) {
            val handshake = handshake(context, peerID, intent)
            if (handshake.optString("status") != "ok") return handshake.put("cmd", "ptt_send")
        }
        val recorder = LiveVoiceCapture(
            File(context.filesDir, "voicenotes/outgoing"),
            LiveVoiceTarget { payload -> mesh.sendVoiceFrame(peerID, payload) },
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
        val content = finalFile.readBytes()
        val packet = BitchatFilePacket(
            fileName = finalFile.name,
            fileSize = content.size.toLong(),
            mimeType = "audio/mp4",
            content = content
        )
        if (peerID == null) mesh.sendFileBroadcast(packet)
        else mesh.sendFilePrivateEncrypted(peerID, packet)
        return ok("ptt_send")
            .put("live", true)
            .put("scope", if (peerID == null) "public" else "dm")
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

    // MARK: - State

    private fun state(context: Context): JSONObject {
        val mesh = mesh(context)
        val peersJson = peerInfosJson(mesh, AppStateStore.peers.value)
        val sessions = JSONObject()
        AppStateStore.peers.value.forEach { peerID ->
            sessions.put(peerID, mesh.getSessionState(peerID).toString())
        }
        return ok("state")
            .put("peer_id", mesh.myPeerID)
            .put("nickname", mesh.nickname)
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

    private fun mesh(context: Context): WearMeshService = WearMeshService.getOrCreate(context)

    private fun peerInfosJson(mesh: WearMeshService, peerIds: List<String>): JSONArray {
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
