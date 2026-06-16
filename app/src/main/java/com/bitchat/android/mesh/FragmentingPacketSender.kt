package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared transport send wrapper that applies bitchat packet fragmentation and
 * transfer progress before a transport writes packets to its concrete medium.
 */
class FragmentingPacketSender(
    private val scope: CoroutineScope,
    private val fragmentManager: FragmentManager?,
    private val logTag: String,
    private val interFragmentDelayMs: Long = 20L
) {
    private val transferJobs = ConcurrentHashMap<String, Job>()

    fun send(
        routed: RoutedPacket,
        description: String,
        sendSingle: (RoutedPacket) -> Boolean
    ): Boolean {
        val transferId = transferIdFor(routed)
        val packets = packetsForTransport(routed.packet) ?: return false
        val total = packets.size

        if (total <= 1) {
            if (transferId != null) {
                TransferProgressManager.start(transferId, 1)
            }
            val sent = sendSingle(routed.copy(packet = packets.first(), transferId = transferId))
            if (sent && transferId != null) {
                TransferProgressManager.progress(transferId, 1, 1)
                TransferProgressManager.complete(transferId, 1)
            }
            return sent
        }

        Log.d(logTag, "Fragmenting packet type ${routed.packet.type} into $total fragments for $description")
        if (transferId != null) {
            TransferProgressManager.start(transferId, total)
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var sent = 0
            for (packet in packets) {
                if (!isActive) return@launch
                if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch

                val fragment = routed.copy(packet = packet, transferId = transferId)
                val delivered = try {
                    sendSingle(fragment)
                } catch (e: Exception) {
                    Log.e(logTag, "Fragment send failed for $description: ${e.message}", e)
                    false
                }

                if (!delivered) {
                    Log.w(logTag, "Stopping fragmented send for $description after $sent/$total fragments")
                    return@launch
                }

                sent += 1
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, sent, total)
                }
                if (sent < total) {
                    delay(interFragmentDelayMs)
                }
            }

            if (transferId != null) {
                TransferProgressManager.complete(transferId, total)
            }
        }

        if (transferId != null) {
            transferJobs[transferId] = job
            job.invokeOnCompletion { transferJobs.remove(transferId, job) }
        }
        job.start()
        return true
    }

    fun cancelTransfer(transferId: String): Boolean {
        val job = transferJobs.remove(transferId) ?: return false
        job.cancel()
        return true
    }

    private fun packetsForTransport(packet: BitchatPacket): List<BitchatPacket>? {
        if (packet.type == MessageType.FRAGMENT.value) {
            return listOf(packet)
        }

        val manager = fragmentManager ?: return listOf(packet)
        return try {
            val fragments = manager.createFragments(packet)
            if (fragments.isEmpty()) {
                Log.e(logTag, "Fragment manager returned no packets for packet type ${packet.type}")
                null
            } else {
                fragments
            }
        } catch (e: Exception) {
            Log.e(logTag, "Fragment creation failed for packet type ${packet.type}: ${e.message}", e)
            null
        }
    }

    private fun transferIdFor(routed: RoutedPacket): String? {
        routed.transferId?.let { return it }
        val packet = routed.packet
        return if (packet.type == MessageType.FILE_TRANSFER.value) {
            sha256Hex(packet.payload)
        } else {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
