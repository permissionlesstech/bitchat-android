package com.bitchat.android.sync

import android.util.Log
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Gossip-based synchronization manager using on-demand GCS filters.
 * Tracks seen public packets (ANNOUNCE, broadcast MESSAGE) and periodically requests sync
 * from neighbors. Responds to REQUEST_SYNC by sending missing packets.
 */
class GossipSyncManager(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val configProvider: ConfigProvider
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
        private const val BOARD_CAPACITY = 200
        private const val BOARD_SYNC_INTERVAL_MS = 60_000L
        private const val RESPONSE_WINDOW_MS = 30_000L
        private const val MAX_RESPONSES_PER_WINDOW = 8
    }

    var delegate: Delegate? = null
    /** BoardStore-backed source of live post and tombstone packets. */
    var boardPacketsProvider: (() -> List<BitchatPacket>)? = null

    // Defaults (configurable constants)
    private val defaultMaxBytes = SyncDefaults.DEFAULT_FILTER_BYTES
    private val defaultFpr = SyncDefaults.DEFAULT_FPR_PERCENT

    // Stored packets for sync:
    // - broadcast messages: keep up to seenCapacity() most recent, keyed by packetId
    private val messages = LinkedHashMap<String, BitchatPacket>()
    // - announcements: only keep latest per sender peerID
    private val latestAnnouncementByPeer = ConcurrentHashMap<String, Pair<String, BitchatPacket>>()
    private val responseTimesByPeer = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val advertisedBoardPeers = ConcurrentHashMap.newKeySet<String>()
    private val observedBoardSyncPeers = ConcurrentHashMap.newKeySet<String>()

    private var periodicJob: Job? = null
    private var boardPeriodicJob: Job? = null
    private var cleanupJob: Job? = null
    fun start() {
        periodicJob?.cancel()
        periodicJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(30_000)
                    sendRequestSync(SyncTypeFlags.PUBLIC_MESSAGES)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic sync error: ${e.message}") }
            }
        }
        boardPeriodicJob?.cancel()
        boardPeriodicJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(BOARD_SYNC_INTERVAL_MS)
                    if (boardPacketsProvider != null) {
                        boardSyncPeers().forEach { peerID ->
                            sendRequestSyncToPeer(peerID, SyncTypeFlags.BOARD)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic board sync error: ${e.message}")
                }
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
        boardPeriodicJob?.cancel(); boardPeriodicJob = null
        cleanupJob?.cancel(); cleanupJob = null
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSync(SyncTypeFlags.PUBLIC_MESSAGES)
            if (boardPacketsProvider != null) {
                boardSyncPeers().forEach { peerID ->
                    sendRequestSyncToPeer(peerID, SyncTypeFlags.BOARD)
                }
            }
        }
    }

    fun scheduleInitialSyncToPeer(peerID: String, delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            val types = if (boardPacketsProvider != null && peerSupportsBoard(peerID)) {
                SyncTypeFlags.PUBLIC_MESSAGES.union(SyncTypeFlags.BOARD)
            } else {
                SyncTypeFlags.PUBLIC_MESSAGES
            }
            sendRequestSyncToPeer(peerID, types)
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {
        // Only ANNOUNCE or broadcast MESSAGE
        val mt = MessageType.fromValue(packet.type)
        val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
        val isAnnouncement = (mt == MessageType.ANNOUNCE)
        if (!isBroadcastMessage && !isAnnouncement) return

        val idBytes = PacketIdUtil.computeIdBytes(packet)
        val id = idBytes.joinToString("") { b -> "%02x".format(b) }

        if (isBroadcastMessage) {
            synchronized(messages) {
                messages[id] = packet
                // Enforce capacity (remove oldest when exceeded)
                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (messages.size > cap) {
                    val it = messages.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
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
            val supportsBoard = IdentityAnnouncement.decode(packet.payload)
                ?.capabilities
                ?.contains(PeerCapabilities.BOARD) == true
            if (supportsBoard) {
                advertisedBoardPeers.add(sender)
            } else {
                advertisedBoardPeers.remove(sender)
            }
            // Enforce capacity (remove oldest when exceeded)
            val cap = configProvider.seenCapacity().coerceAtLeast(1)
            while (latestAnnouncementByPeer.size > cap) {
                val it = latestAnnouncementByPeer.entries.iterator()
                if (it.hasNext()) {
                    val evictedPeer = it.next().key
                    it.remove()
                    advertisedBoardPeers.remove(evictedPeer)
                    observedBoardSyncPeers.remove(evictedPeer)
                } else {
                    break
                }
            }
        }
    }

    private fun sendRequestSync(types: SyncTypeFlags) {
        val payload = buildGcsPayload(types)

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

    private fun sendRequestSyncToPeer(peerID: String, types: SyncTypeFlags) {
        val payload = buildGcsPayload(types)

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
        val requestedTypes = request.types ?: SyncTypeFlags.PUBLIC_MESSAGES
        if (requestedTypes.contains(MessageType.BOARD_POST)) {
            // This is the compatibility signal used by iOS builds that
            // understand board sync but do not yet advertise the BOARD bit.
            observedBoardSyncPeers.add(fromPeerID.lowercase())
        }
        if (!shouldRespondTo(fromPeerID)) {
            Log.w(TAG, "Rate-limited REQUEST_SYNC from ${fromPeerID.take(8)}")
            return
        }
        // Decode GCS into sorted set for membership checks
        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)
        fun mightContain(id: ByteArray): Boolean {
            val v = GCSFilter.h64(id) % request.m
            val nonZeroV = if (v == 0L) 1L else v
            return GCSFilter.contains(sorted, nonZeroV)
        }

        // Announces are exempt from the since cursor: they carry verification keys.
        if (requestedTypes.contains(MessageType.ANNOUNCE)) {
            for ((_, pair) in latestAnnouncementByPeer.entries) {
                val (id, pkt) = pair
                val idBytes = hexToBytes(id)
                if (!mightContain(idBytes)) {
                    val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                    delegate?.sendPacketToPeer(fromPeerID, toSend)
                    Log.d(TAG, "Sent sync announce: Type ${toSend.type} from ${toSend.senderID.toHexString()} to $fromPeerID packet id ${idBytes.toHexString()}")
                }
            }
        }

        if (requestedTypes.contains(MessageType.MESSAGE)) {
            val toSendMsgs = synchronized(messages) { messages.values.toList() }
            for (pkt in toSendMsgs) {
                if (request.sinceTimestamp != null && pkt.timestamp < request.sinceTimestamp) continue
                val idBytes = PacketIdUtil.computeIdBytes(pkt)
                if (!mightContain(idBytes)) {
                    val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                    delegate?.sendPacketToPeer(fromPeerID, toSend)
                    Log.d(TAG, "Sent sync message: Type ${toSend.type} to $fromPeerID packet id ${idBytes.toHexString()}")
                }
            }
        }

        if (requestedTypes.contains(MessageType.BOARD_POST)) {
            for (pkt in boardPacketsProvider?.invoke().orEmpty()) {
                if (request.sinceTimestamp != null && pkt.timestamp < request.sinceTimestamp) continue
                val idBytes = PacketIdUtil.computeIdBytes(pkt)
                if (!mightContain(idBytes)) {
                    val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                    delegate?.sendPacketToPeer(fromPeerID, toSend)
                    Log.d(TAG, "Sent sync board packet to $fromPeerID packet id ${idBytes.toHexString()}")
                }
            }
        }
    }

    private fun shouldRespondTo(peerID: String): Boolean {
        val now = System.currentTimeMillis()
        val times = responseTimesByPeer.computeIfAbsent(peerID) { ArrayDeque() }
        return synchronized(times) {
            while (times.isNotEmpty() && now - times.first() >= RESPONSE_WINDOW_MS) {
                times.removeFirst()
            }
            if (times.size >= MAX_RESPONSES_PER_WINDOW) {
                false
            } else {
                times.addLast(now)
                true
            }
        }
    }

    private fun peerSupportsBoard(peerID: String): Boolean {
        val normalized = peerID.lowercase()
        return normalized in advertisedBoardPeers || normalized in observedBoardSyncPeers
    }

    private fun boardSyncPeers(): List<String> =
        latestAnnouncementByPeer.keys.filter(::peerSupportsBoard)

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
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

    private fun buildGcsPayload(types: SyncTypeFlags): ByteArray {
        val list = ArrayList<BitchatPacket>()
        if (types.contains(MessageType.ANNOUNCE)) {
            for ((_, pair) in latestAnnouncementByPeer) {
                list.add(pair.second)
            }
        }
        if (types.contains(MessageType.MESSAGE)) {
            synchronized(messages) {
                list.addAll(messages.values)
            }
        }
        if (types.contains(MessageType.BOARD_POST)) {
            list.addAll(boardPacketsProvider?.invoke().orEmpty())
        }
        // sort by timestamp desc, then take up to min(seenCapacity, fit capacity)
        list.sortByDescending { it.timestamp.toLong() }

        val maxBytes = try { configProvider.gcsMaxBytes() } catch (_: Exception) { defaultMaxBytes }
        val fpr = try { configProvider.gcsTargetFpr() } catch (_: Exception) { defaultFpr }
        val p = GCSFilter.deriveP(fpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(maxBytes, p)
        val cap = if (types == SyncTypeFlags.BOARD) {
            BOARD_CAPACITY
        } else {
            configProvider.seenCapacity().coerceAtLeast(1)
        }
        val takeN = minOf(nMax, cap, list.size)
        if (takeN <= 0) {
            val p0 = GCSFilter.deriveP(fpr)
            return RequestSyncPacket(
                p = p0,
                m = 1,
                data = ByteArray(0),
                types = types
            ).encode()
        }
        val included = list.take(takeN)
        val ids = included.map { pkt -> PacketIdUtil.computeIdBytes(pkt) }
        val params = GCSFilter.buildFilter(ids, maxBytes, fpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        val sinceTimestamp =
            if (params.includedCount in 1 until list.size) {
                included[params.includedCount - 1].timestamp
            } else {
                null
            }
        return RequestSyncPacket(
            p = params.p,
            m = mVal,
            data = params.data,
            types = types,
            sinceTimestamp = sinceTimestamp
        ).encode()
    }

    // Periodically remove stale announcements and all their messages
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

        if (stalePeers.isEmpty()) return

        // Remove announcements and their messages
        var totalPrunedMsgs = 0
        for (peerID in stalePeers) {
            // Count messages to be pruned for logging
            val toRemove = mutableListOf<String>()
            synchronized(messages) {
                for ((id, message) in messages) {
                    val sender = message.senderID.joinToString("") { b -> "%02x".format(b) }
                    if (sender == peerID) toRemove.add(id)
                }
            }
            totalPrunedMsgs += toRemove.size

            // Reuse existing removal which also clears announcement entry
            removeAnnouncementForPeer(peerID)
        }

        Log.d(TAG, "Pruned ${stalePeers.size} stale announcements and $totalPrunedMsgs messages")
    }

    // Explicitly remove stored announcement for a given peer (hex ID)
    fun removeAnnouncementForPeer(peerID: String) {
        val key = peerID.lowercase()
        advertisedBoardPeers.remove(key)
        observedBoardSyncPeers.remove(key)
        if (latestAnnouncementByPeer.remove(key) != null) {
            Log.d(TAG, "Removed stored announcement for peer $peerID")
        }

        // Collect IDs to remove first to avoid modifying collection while iterating
        val idsToRemove = mutableListOf<String>()
        synchronized(messages) {
            for ((id, message) in messages) {
                val sender = message.senderID.joinToString("") { b -> "%02x".format(b) }
                if (sender == key) {
                    idsToRemove.add(id)
                }
            }
        }
        
        // Now remove the collected IDs
        synchronized(messages) {
            for (id in idsToRemove) {
                messages.remove(id)
            }
        }
        
        if (idsToRemove.isNotEmpty()) {
            Log.d(TAG, "Pruned ${idsToRemove.size} messages with senders without announcements")
        }
    }
}
