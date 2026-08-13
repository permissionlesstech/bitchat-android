package com.bitchat.android.sync

import android.util.Log
import com.bitchat.android.mesh.BluetoothPacketBroadcaster
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import java.io.File
import java.nio.file.StandardCopyOption

/**
 * Gossip-based synchronization manager using on-demand GCS filters.
 * Tracks seen public packets (ANNOUNCE, broadcast MESSAGE) and periodically requests sync
 * from neighbors. Responds to REQUEST_SYNC by sending missing packets.
 */
class GossipSyncManager(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val configProvider: ConfigProvider,
    context: Context? = null
) {
    interface Delegate {
        fun sendPacket(packet: BitchatPacket)
        fun sendPacketToPeer(peerID: String, packet: BitchatPacket)
        fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket
    }

    interface ConfigProvider {
        fun seenCapacity(): Int // max packets we sync per request (cap across types)
        fun gcsMaxBytes(): Int
        fun gcsTargetFpr(): Double // percent -> 0.0..1.0
    }

    companion object {
        private const val TAG = "GossipSyncManager"
        const val PUBLIC_MESSAGE_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        const val FRAGMENT_MAX_AGE_MS = 15 * 60 * 1000L
        private const val ARCHIVE_FILE = "gossip-public-history.bin"
    }

    var delegate: Delegate? = null

    // Defaults (configurable constants)
    private val defaultMaxBytes = SyncDefaults.DEFAULT_FILTER_BYTES
    private val defaultFpr = SyncDefaults.DEFAULT_FPR_PERCENT

    // Stored packets for sync:
    // - broadcast messages: keep up to seenCapacity() most recent, keyed by packetId
    private val messages = LinkedHashMap<String, BitchatPacket>()
    private val fragments = LinkedHashMap<String, BitchatPacket>()
    private val archiveFile = context?.applicationContext?.filesDir?.let { File(it, ARCHIVE_FILE) }
    private var restoringArchive = false
    // - announcements: only keep latest per sender peerID
    private val latestAnnouncementByPeer = ConcurrentHashMap<String, Pair<String, BitchatPacket>>()

    private var periodicJob: Job? = null
    private var cleanupJob: Job? = null
    init { restoreArchive() }
    fun start() {
        periodicJob?.cancel()
        periodicJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(30_000)
                    sendRequestSync()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic sync error: ${e.message}") }
            }
        }

        // Start periodic cleanup of stale announcements and messages
        cleanupJob?.cancel()
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(com.bitchat.android.util.AppConstants.Sync.CLEANUP_INTERVAL_MS)
                    pruneStaleAnnouncements()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic cleanup error: ${e.message}") }
            }
        }
    }

    fun stop() {
        periodicJob?.cancel(); periodicJob = null
        cleanupJob?.cancel(); cleanupJob = null
    }

    @Synchronized
    fun clear() {
        synchronized(messages) {
            messages.clear()
        }
        synchronized(fragments) { fragments.clear() }
        latestAnnouncementByPeer.clear()
        archiveFile?.delete()
        Log.d(TAG, "Cleared all gossip sync messages and announcements")
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSync()
        }
    }

    fun scheduleInitialSyncToPeer(peerID: String, delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSyncToPeer(peerID)
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {
        // Only ANNOUNCE or broadcast MESSAGE
        val mt = MessageType.fromValue(packet.type)
        val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
        val isBroadcastFile = mt == MessageType.FILE_TRANSFER &&
            (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
        val isAnnouncement = (mt == MessageType.ANNOUNCE)
        val isFragment = (mt == MessageType.FRAGMENT || isBroadcastFile) &&
            (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
        if (!isBroadcastMessage && !isAnnouncement && !isFragment) return

        val idBytes = PacketIdUtil.computeIdBytes(packet)
        val id = idBytes.joinToString("") { b -> "%02x".format(b) }

        if (isBroadcastMessage) {
            val now = System.currentTimeMillis()
            val age = now - packet.timestamp.toLong()
            if (age !in 0..PUBLIC_MESSAGE_MAX_AGE_MS) return
            synchronized(messages) {
                messages[id] = packet
                // Enforce capacity (remove oldest when exceeded)
                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (messages.size > cap) {
                    val it = messages.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
            if (!restoringArchive) persistArchive()
        } else if (isFragment) {
            val age = System.currentTimeMillis() - packet.timestamp.toLong()
            if (age !in 0..FRAGMENT_MAX_AGE_MS) return
            synchronized(fragments) {
                fragments[id] = packet
                while (fragments.size > configProvider.seenCapacity().coerceAtLeast(1)) {
                    val iterator = fragments.entries.iterator()
                    if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
                }
            }
        } else if (isAnnouncement) {
            // Ignore stale announcements older than STALE_PEER_TIMEOUT
            val now = System.currentTimeMillis()
            val age = now - packet.timestamp.toLong()
            if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                Log.d(TAG, "Ignoring stale ANNOUNCE (age=${age}ms > ${com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
                return
            }
            // senderID is fixed-size 8 bytes; map to hex string for key
            val sender = packet.senderID.joinToString("") { b -> "%02x".format(b) }
            latestAnnouncementByPeer[sender] = id to packet
            // Enforce capacity (remove oldest when exceeded)
            val cap = configProvider.seenCapacity().coerceAtLeast(1)
            while (latestAnnouncementByPeer.size > cap) {
                val it = latestAnnouncementByPeer.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() } else break
            }
        }
    }

    private fun sendRequestSync() {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbors only
        )
        // Sign and broadcast
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacket(signed)
    }

    private fun sendRequestSyncToPeer(peerID: String) {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(peerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbor only
        )
        Log.d(TAG, "Sending sync request to $peerID (${payload.size} bytes)")
        // Sign and send directly to peer
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacketToPeer(peerID, signed)
    }

    fun handleRequestSync(fromPeerID: String, request: RequestSyncPacket) {
        // Decode GCS into sorted set for membership checks
        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)
        fun mightContain(id: ByteArray): Boolean {
            val v = GCSFilter.h64(id) % request.m
            val nonZeroV = if (v == 0L) 1L else v
            return GCSFilter.contains(sorted, nonZeroV)
        }

        // 1) Announcements: send latest per peerID if remote doesn't have them
        for ((_, pair) in latestAnnouncementByPeer.entries) {
            val (id, pkt) = pair
            val idBytes = hexToBytes(id)
            if (!mightContain(idBytes)) {
                // Send original packet unchanged to requester only (keep local TTL)
                val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                delegate?.sendPacketToPeer(fromPeerID, toSend)
                Log.d(TAG, "Sent sync announce: Type ${toSend.type} from ${toSend.senderID.toHexString()} to $fromPeerID packet id ${idBytes.toHexString()}")
            }
        }

        // 2) Broadcast messages: send all they lack
        val toSendMsgs = synchronized(messages) { messages.values.toList() }
        for (pkt in toSendMsgs) {
            val idBytes = PacketIdUtil.computeIdBytes(pkt)
            if (!mightContain(idBytes)) {
                val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                delegate?.sendPacketToPeer(fromPeerID, toSend)
                Log.d(TAG, "Sent sync message: Type ${toSend.type} to $fromPeerID packet id ${idBytes.toHexString()}")
            }
        }
        val toSendFragments = synchronized(fragments) { fragments.values.toList() }
        for (pkt in toSendFragments) {
            val idBytes = PacketIdUtil.computeIdBytes(pkt)
            if (!mightContain(idBytes)) {
                delegate?.sendPacketToPeer(fromPeerID, pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS))
            }
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) result[index] = byte
            tempID = tempID.substring(2)
            index++
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i/2] = clean.substring(i, i+2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun buildGcsPayload(): ByteArray {
        // Collect candidates: latest announcement per peer + recent broadcast messages
        val list = ArrayList<BitchatPacket>()
        // announcements
        for ((_, pair) in latestAnnouncementByPeer) {
            list.add(pair.second)
        }
        // messages
        synchronized(messages) {
            list.addAll(messages.values)
        }
        synchronized(fragments) { list.addAll(fragments.values) }
        // sort by timestamp desc, then take up to min(seenCapacity, fit capacity)
        list.sortByDescending { it.timestamp.toLong() }

        val maxBytes = try { configProvider.gcsMaxBytes() } catch (_: Exception) { defaultMaxBytes }
        val fpr = try { configProvider.gcsTargetFpr() } catch (_: Exception) { defaultFpr }
        val p = GCSFilter.deriveP(fpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(maxBytes, p)
        val cap = configProvider.seenCapacity().coerceAtLeast(1)
        val takeN = minOf(nMax, cap, list.size)
        if (takeN <= 0) {
            val p0 = GCSFilter.deriveP(fpr)
            return RequestSyncPacket(p = p0, m = 1, data = ByteArray(0)).encode()
        }
        val ids = list.take(takeN).map { pkt -> PacketIdUtil.computeIdBytes(pkt) }
        val params = GCSFilter.buildFilter(ids, maxBytes, fpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        return RequestSyncPacket(p = params.p, m = mVal, data = params.data).encode()
    }

    // Announcements age out quickly; public history remains independently sync-able for six hours.
    private fun pruneStaleAnnouncements() {
        val now = System.currentTimeMillis()
        val stalePeers = mutableListOf<String>()

        // Identify stale announcements by age
        for ((peerID, pair) in latestAnnouncementByPeer.entries) {
            val pkt = pair.second
            val age = now - pkt.timestamp.toLong()
            if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                stalePeers.add(peerID)
            }
        }

        var changed = false
        for (peerID in stalePeers) {
            changed = latestAnnouncementByPeer.remove(peerID) != null || changed
        }
        synchronized(messages) {
            changed = messages.entries.removeAll { now - it.value.timestamp.toLong() > PUBLIC_MESSAGE_MAX_AGE_MS } || changed
        }
        synchronized(fragments) {
            fragments.entries.removeAll { now - it.value.timestamp.toLong() > FRAGMENT_MAX_AGE_MS }
        }
        if (changed) persistArchive()
    }

    // Explicitly remove stored announcement for a given peer (hex ID)
    fun removeAnnouncementForPeer(peerID: String) {
        val key = peerID.lowercase()
        if (latestAnnouncementByPeer.remove(key) != null) {
            Log.d(TAG, "Removed stored announcement for peer $peerID")
        }

    }

    private fun restoreArchive() {
        val file = archiveFile ?: return
        if (!file.exists()) return
        try {
            restoringArchive = true
            val input = java.io.DataInputStream(file.inputStream().buffered())
            val count = input.readInt().coerceIn(0, configProvider.seenCapacity())
            repeat(count) {
                val length = input.readInt()
                if (length <= 0 || length > com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH + 256) return@repeat
                val packet = com.bitchat.android.protocol.BinaryProtocol.decode(input.readNBytes(length)) ?: return@repeat
                onPublicPacketSeen(packet)
            }
            input.close()
        } catch (_: Exception) {
            synchronized(messages) { messages.clear() }
        } finally {
            restoringArchive = false
        }
    }

    @Synchronized
    private fun persistArchive() {
        val file = archiveFile ?: return
        try {
            val packets = synchronized(messages) { messages.values.toList() }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            java.io.DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(packets.size)
                packets.forEach { packet ->
                    val encoded = com.bitchat.android.protocol.BinaryProtocol.encode(packet, padding = false) ?: return@forEach
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                temporary.delete()
            }
        } catch (_: Exception) { }
    }
}
