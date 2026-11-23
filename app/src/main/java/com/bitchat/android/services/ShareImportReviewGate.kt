package com.bitchat.android.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stages incoming shared content (text or media URIs from Android share sheets)
 * so the user must explicitly review and confirm before anything is sent or imported.
 */
data class PendingSharedContent(
    val text: String? = null,
    val mediaUris: List<String> = emptyList(),
    val sourcePackage: String? = null
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && mediaUris.isEmpty()
}

object ShareImportReviewGate {
    private val _pending = MutableStateFlow<PendingSharedContent?>(null)
    val pending: StateFlow<PendingSharedContent?> = _pending.asStateFlow()

    /**
     * Stages shared content for user review. Empty payloads are ignored.
     * Returns true if content was staged and awaits confirmation.
     */
    fun stage(content: PendingSharedContent): Boolean {
        if (content.isEmpty) return false
        _pending.value = content
        return true
    }

    /**
     * Confirms and consumes the staged shared content, returning it to the caller
     * for delivery/import, or null if nothing is pending.
     */
    fun confirmAndConsume(): PendingSharedContent? {
        val current = _pending.value ?: return null
        _pending.value = null
        return current
    }

    /**
     * Discards any staged shared content without sending or importing it.
     */
    fun discard() {
        _pending.value = null
    }
}
