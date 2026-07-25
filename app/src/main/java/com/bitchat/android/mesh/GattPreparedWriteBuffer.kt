package com.bitchat.android.mesh

import java.util.concurrent.ConcurrentHashMap

/**
 * Reassembles BLE prepared ("reliable" / long) writes on the GATT server side.
 *
 * When a peer (notably iOS) needs to send a payload larger than the negotiated
 * ATT MTU, it uses the ATT prepared-write procedure: the payload arrives as a
 * series of `onCharacteristicWriteRequest` callbacks with `preparedWrite == true`
 * and increasing `offset`s, and is finalized by a single `onExecuteWrite`. Each
 * individual chunk is only a slice of the packet, so parsing a chunk on its own
 * fails. This buffer collects the chunks per device and returns the concatenated
 * payload once the write is executed.
 *
 * All operations are thread-safe: these GATT callbacks can arrive on binder
 * threads and several devices may be writing concurrently. Each device is keyed
 * by a stable string (its BLE address) so device buffers are isolated.
 *
 * A per-device size cap protects against a broken or malicious peer streaming an
 * unbounded prepared write to exhaust memory; once exceeded the buffer is dropped
 * and [execute] returns null.
 */
class GattPreparedWriteBuffer(
    private val maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD_SIZE
) {
    companion object {
        /**
         * Generous upper bound for a single reassembled payload. Real bitchat
         * packets are far smaller (well under the low-KB range), so this only
         * ever trips for abusive peers.
         */
        const val DEFAULT_MAX_PAYLOAD_SIZE = 512 * 1024 // 512 KiB
    }

    private class DeviceBuffer {
        var data: ByteArray = ByteArray(0)
        var length: Int = 0
        var overflowed: Boolean = false
    }

    private val buffers = ConcurrentHashMap<String, DeviceBuffer>()

    /**
     * Buffer a single prepared-write chunk for [deviceKey], placing [value] at
     * [offset] within that device's growing payload.
     *
     * Chunks are written at their byte offset, so reassembly is correct even if
     * chunks are delivered out of order. Returns true if the chunk was accepted,
     * or false if it was rejected: a negative offset, or a write that would push
     * the payload past [maxPayloadSize]. On overflow the device's buffer is
     * marked so that [execute] yields null and the oversized payload is dropped.
     */
    fun append(deviceKey: String, offset: Int, value: ByteArray): Boolean {
        if (offset < 0) return false
        val buf = buffers.getOrPut(deviceKey) { DeviceBuffer() }
        synchronized(buf) {
            if (buf.overflowed) return false
            val end = offset.toLong() + value.size.toLong()
            if (end > maxPayloadSize.toLong()) {
                // Drop what we have and remember the overflow until the write is
                // finalized/cancelled, so we never allocate beyond the cap.
                buf.overflowed = true
                buf.data = ByteArray(0)
                buf.length = 0
                return false
            }
            val endInt = end.toInt()
            if (endInt > buf.data.size) {
                val newCapacity = maxOf(endInt, buf.data.size * 2).coerceAtMost(maxPayloadSize)
                buf.data = buf.data.copyOf(maxOf(newCapacity, endInt))
            }
            System.arraycopy(value, 0, buf.data, offset, value.size)
            if (endInt > buf.length) buf.length = endInt
            return true
        }
    }

    /**
     * Finalize the prepared write for [deviceKey] and return the reassembled
     * payload, or null if nothing was buffered or the buffer overflowed. The
     * device's buffer is always removed.
     */
    fun execute(deviceKey: String): ByteArray? {
        val buf = buffers.remove(deviceKey) ?: return null
        synchronized(buf) {
            if (buf.overflowed || buf.length == 0) return null
            return buf.data.copyOf(buf.length)
        }
    }

    /**
     * Discard any buffered chunks for [deviceKey]. Used when a prepared write is
     * cancelled or when the device disconnects, to avoid leaking buffers.
     */
    fun cancel(deviceKey: String) {
        buffers.remove(deviceKey)
    }

    /** Number of devices currently holding buffered chunks (diagnostics/tests). */
    fun activeDeviceCount(): Int = buffers.size
}
