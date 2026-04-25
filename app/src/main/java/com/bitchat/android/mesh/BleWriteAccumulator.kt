package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Reassembles characteristic writes that arrive in multiple offset chunks.
 *
 * CoreBluetooth may split a single packet across multiple writes when acting as
 * the central. Android's GATT server callback receives those chunks one by one,
 * so we keep a per-device sparse buffer and only hand the packet upstream once
 * the accumulated bytes decode successfully.
 */
class BleWriteAccumulator {

    private data class PendingWrite(
        val buffer: ByteArray,
        val receivedRanges: List<IntRange>
    )

    private val pendingWrites = ConcurrentHashMap<String, PendingWrite>()

    fun append(deviceAddress: String, offset: Int, chunk: ByteArray): BitchatPacket? {
        if (chunk.isEmpty()) {
            return null
        }

        val current = pendingWrites[deviceAddress]
        val existing = if (offset == 0 && current?.receivedRanges?.any { it.first == 0 } == true) {
            null
        } else {
            current
        }
        val end = offset + chunk.size
        val existingBuffer = existing?.buffer ?: ByteArray(0)
        val combined = if (existingBuffer.size >= end) {
            existingBuffer.copyOf()
        } else {
            existingBuffer.copyOf(end)
        }
        chunk.copyInto(combined, destinationOffset = offset)
        val mergedRanges = mergeRanges(existing?.receivedRanges.orEmpty(), IntRange(offset, end - 1))
        val pendingWrite = PendingWrite(combined, mergedRanges)
        pendingWrites[deviceAddress] = pendingWrite

        if (!isContiguousFromStart(pendingWrite)) {
            return null
        }

        val packet = BitchatPacket.fromBinaryData(combined) ?: return null
        val canonicalEncoding = packet.toBinaryData() ?: return null
        if (!canonicalEncoding.contentEquals(combined)) {
            return null
        }
        pendingWrites.remove(deviceAddress)
        return packet
    }

    fun clear(deviceAddress: String) {
        pendingWrites.remove(deviceAddress)
    }

    fun clearAll() {
        pendingWrites.clear()
    }

    private fun mergeRanges(existing: List<IntRange>, next: IntRange): List<IntRange> {
        val sorted = buildList {
            addAll(existing)
            add(next)
        }.sortedBy { it.first }
        if (sorted.isEmpty()) {
            return emptyList()
        }

        val merged = mutableListOf<IntRange>()
        var current = sorted.first()
        for (candidate in sorted.drop(1)) {
            current = if (candidate.first <= current.last + 1) {
                current.first..maxOf(current.last, candidate.last)
            } else {
                merged.add(current)
                candidate
            }
        }
        merged.add(current)
        return merged
    }

    private fun isContiguousFromStart(pendingWrite: PendingWrite): Boolean {
        val onlyRange = pendingWrite.receivedRanges.singleOrNull() ?: return false
        return onlyRange.first == 0 && onlyRange.last + 1 == pendingWrite.buffer.size
    }
}
