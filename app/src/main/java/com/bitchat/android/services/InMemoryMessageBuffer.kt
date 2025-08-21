package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import java.util.LinkedList

/**
 * Process-local, in-memory message buffer used while the UI is closed.
 * - Never touches disk
 * - Cleared on process death, reboot, or panic
 */
object InMemoryMessageBuffer {
    private const val MAX_MESSAGES = 500
    private val lock = Any()
    private val queue: LinkedList<BitchatMessage> = LinkedList()

    fun add(message: BitchatMessage) {
        synchronized(lock) {
            // Deduplicate by id
            if (queue.any { it.id == message.id }) return
            queue.addLast(message)
            while (queue.size > MAX_MESSAGES) {
                queue.removeFirst()
            }
        }
    }

    fun drain(): List<BitchatMessage> {
        synchronized(lock) {
            if (queue.isEmpty()) return emptyList()
            val copy = ArrayList<BitchatMessage>(queue)
            queue.clear()
            return copy
        }
    }

    fun clear() {
        synchronized(lock) { queue.clear() }
    }
}

