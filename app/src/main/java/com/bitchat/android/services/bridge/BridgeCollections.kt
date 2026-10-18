package com.bitchat.android.services.bridge


internal class BoundedIdSet(private val capacity: Int) {
    private val values = LinkedHashSet<String>()

    fun add(id: String): Boolean {
        if (!values.add(id)) return false
        while (values.size > capacity) values.remove(values.first())
        return true
    }

    fun contains(id: String): Boolean = id in values

    fun clear() = values.clear()
}

/**
 * Owns one logical relay subscription while assigning a distinct wire ID to
 * every replacement. Relay CLOSE/REQ writes may execute out of order, but a
 * delayed close can only affect the retired generation.
 *
 * Callers keep this object confined to their coordinator dispatcher.
 */
internal class RelaySubscriptionSlot(private val idPrefix: String) {
    private var generation = 0L
    private var activeId: String? = null

    fun replace(close: (String) -> Unit, open: (String) -> Unit): String {
        activeId?.let(close)
        val replacementId = "$idPrefix-${++generation}"
        activeId = replacementId
        try {
            open(replacementId)
        } catch (error: Throwable) {
            activeId = null
            throw error
        }
        return replacementId
    }

    fun close(close: (String) -> Unit) {
        val id = activeId ?: return
        activeId = null
        close(id)
    }
}
