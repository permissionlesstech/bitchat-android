package com.bitchat.android.sync

import android.util.Log
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Gossip-based synchronization manager using rotating Bloom filters.
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
        fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket
    }

    interface ConfigProvider {
        fun seenCapacity(): Int // total stored broadcast messages (not including announcements map)
        fun bloomMaxBytes(): Int
        fun bloomTargetFpr(): Double
    }

    companion object { private const val TAG = "GossipSyncManager" }

    var delegate: Delegate? = null

    // Bloom filter
    @Volatile private var bloom = SeenPacketsBloomFilter(
        maxBytes = configProvider.bloomMaxBytes(),
        targetFpr = configProvider.bloomTargetFpr()
    )

    // Stored packets for sync:
    // - broadcast messages: keep up to seenCapacity() most recent, keyed by packetId
    private val messages = LinkedHashMap<String, BitchatPacket>()
    // - announcements: only keep latest per sender peerID
    private val latestAnnouncementByPeer = ConcurrentHashMap<String, Pair<String, BitchatPacket>>()

    private var periodicJob: Job? = null

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
    }

    fun stop() {
        periodicJob?.cancel(); periodicJob = null
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSync()
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {
        // Only ANNOUNCE or broadcast MESSAGE
        val mt = MessageType.fromValue(packet.type)
        val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
        val isAnnouncement = (mt == MessageType.ANNOUNCE)
        if (!isBroadcastMessage && !isAnnouncement) return

        val idBytes = PacketIdUtil.computeIdBytes(packet)
        bloom.add(idBytes)
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
            // senderID is fixed-size 8 bytes; map to hex string for key
            val sender = packet.senderID.joinToString("") { b -> "%02x".format(b) }
            latestAnnouncementByPeer[sender] = id to packet
        }
    }

    private fun sendRequestSync() {
        val snap = bloom.snapshotActive()
        val payload = RequestSyncPacket(
            mBytes = snap.mBytes,
            k = snap.k,
            bits = snap.bits
        ).encode()

        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            signature = null,
            ttl = 0u // neighbors only
        )
        // Sign and broadcast
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacket(signed)
    }

    fun handleRequestSync(fromPeerID: String, request: RequestSyncPacket) {
        // Build a checker against provided bloom (mBytes and k are supplied).
        val remoteChecker = object {
            val mBits = request.mBytes * 8
            val k = request.k
            fun mightContain(id: ByteArray): Boolean {
                // Double hashing same as SeenPacketsBloomFilter to ensure compatibility
                var h1 = 1469598103934665603L
                var h2 = 0x27d4eb2f165667c5L
                for (b in id) {
                    h1 = (h1 xor (b.toLong() and 0xFF)) * 1099511628211L
                    h2 = (h2 xor (b.toLong() and 0xFF)) * 0x100000001B3L
                }
                for (i in 0 until k) {
                    val combined = (h1 + i * h2)
                    val idx = ((combined and Long.MAX_VALUE) % mBits).toInt()
                    val byteIndex = idx / 8
                    val bitIndex = idx % 8
                    val bit = ((request.bits[byteIndex].toInt() shr (7 - bitIndex)) and 1) == 1
                    if (!bit) return false
                }
                return true
            }
        }

        // 1) Announcements: send latest per peerID if remote doesn't have them
        for ((_, pair) in latestAnnouncementByPeer.entries) {
            val (id, pkt) = pair
            val idBytes = hexToBytes(id)
            if (!remoteChecker.mightContain(idBytes)) {
                // Send original packet with TTL=1 to keep local and avoid signature issues
                val toSend = pkt.copy(ttl = 0u)
                delegate?.sendPacket(toSend)
            }
        }

        // 2) Broadcast messages: send all they lack
        val toSendMsgs = synchronized(messages) { messages.values.toList() }
        for (pkt in toSendMsgs) {
            val idBytes = PacketIdUtil.computeIdBytes(pkt)
            if (!remoteChecker.mightContain(idBytes)) {
                val toSend = pkt.copy(ttl = 0u)
                delegate?.sendPacket(toSend)
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
}
