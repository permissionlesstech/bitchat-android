package com.bitchat.android.nostr

/**
 * Builds NIP-09 (kind 5) event deletion request tags and content for location
 * notes so authors can request removal of previously published notes (issue #639).
 */
object LocationNoteDeletionBuilder {

    const val KIND_EVENT_DELETION: Int = 5

    data class DeletionSpec(
        val kind: Int = KIND_EVENT_DELETION,
        val tags: List<List<String>>,
        val content: String
    )

    /**
     * Constructs a NIP-09 deletion spec referencing one or more event IDs.
     *
     * @throws IllegalArgumentException if [eventIds] is empty or contains blank IDs.
     */
    fun build(
        eventIds: List<String>,
        reason: String = "",
        noteKind: Int? = null
    ): DeletionSpec {
        require(eventIds.isNotEmpty()) { "At least one event ID is required for NIP-09 deletion" }
        val normalizedIds = eventIds.map { it.trim() }
        require(normalizedIds.all { it.isNotEmpty() }) { "Event IDs must not be blank" }

        val tags = mutableListOf<List<String>>()
        for (id in normalizedIds.distinct()) {
            tags.add(listOf("e", id))
        }
        if (noteKind != null) {
            tags.add(listOf("k", noteKind.toString()))
        }
        return DeletionSpec(
            kind = KIND_EVENT_DELETION,
            tags = tags,
            content = reason.trim()
        )
    }
}
