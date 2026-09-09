package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small encrypted, immediately observable preferences for conversation-list organization.
 *
 * Message history remains in SQLite; these compact sets and drafts belong in the app's existing
 * Keystore-backed preference store. Panic clearing the identity store also removes these values.
 */
internal class ConversationListPreferences private constructor(
    private val stateManager: SecureIdentityStateManager,
    private val canonicalize: (String) -> String
) {
    private constructor(context: Context) : this(
        SecureIdentityStateManager(context.applicationContext),
        ContactDirectory::canonicalConversationId
    )

    internal constructor(
        stateManager: SecureIdentityStateManager,
        testOnly: Boolean,
        canonicalize: (String) -> String = ContactDirectory::canonicalConversationId
    ) : this(stateManager, canonicalize) {
        require(testOnly) { "Injected conversation preferences are test-only" }
    }

    companion object {
        private const val PINNED_KEY = "conversation_pinned_v1"
        private const val MUTED_KEY = "conversation_muted_v1"
        private const val DRAFTS_KEY = "conversation_drafts_v1"
        private const val MAX_DRAFT_CHARS = 8_000
        private const val MAX_DRAFTS = 50
        private const val MAX_DRAFT_CHARS_TOTAL = 128_000

        @Volatile
        private var instance: ConversationListPreferences? = null

        fun getInstance(context: Context): ConversationListPreferences =
            instance ?: synchronized(this) {
                instance ?: ConversationListPreferences(context.applicationContext).also {
                    instance = it
                }
            }
    }

    private val _pinned = MutableStateFlow(loadSet(PINNED_KEY))
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()
    private val _muted = MutableStateFlow(loadSet(MUTED_KEY))
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()
    private val _drafts = MutableStateFlow(loadDrafts())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    fun togglePinned(conversationID: String) {
        _pinned.value = _pinned.value.toggle(normalize(conversationID))
        saveSet(PINNED_KEY, _pinned.value)
    }

    fun toggleMuted(conversationID: String) {
        _muted.value = _muted.value.toggle(normalize(conversationID))
        saveSet(MUTED_KEY, _muted.value)
    }

    fun isMuted(conversationID: String): Boolean =
        normalize(conversationID) in _muted.value

    fun isPinned(conversationID: String): Boolean =
        normalize(conversationID) in _pinned.value

    fun draftFor(conversationID: String): String? =
        _drafts.value[normalize(conversationID)]

    fun setDraft(conversationID: String, text: String) {
        val key = normalize(conversationID)
        val updated = _drafts.value.toMutableMap()
        // Reinsert edited drafts at the end so bounded eviction approximates least-recently-used.
        updated.remove(key)
        val bounded = text.take(MAX_DRAFT_CHARS)
        if (bounded.isNotBlank()) updated[key] = bounded
        val retained = boundDrafts(updated)
        _drafts.value = retained
        saveDrafts(retained)
    }

    fun removeConversation(conversationID: String) {
        val key = normalize(conversationID)
        _pinned.value = _pinned.value - key
        _muted.value = _muted.value - key
        _drafts.value = _drafts.value - key
        saveSet(PINNED_KEY, _pinned.value)
        saveSet(MUTED_KEY, _muted.value)
        saveDrafts(_drafts.value)
    }

    /**
     * Re-key list preferences when a transient mesh ID becomes a stable contact identity.
     * Without this, pin, mute, and draft state appears to disappear after a Noise/favorite update.
     */
    fun canonicalizeAliases() {
        val canonicalPinned = _pinned.value.mapTo(linkedSetOf(), ::normalize)
        val canonicalMuted = _muted.value.mapTo(linkedSetOf(), ::normalize)
        val canonicalDrafts = linkedMapOf<String, String>()
        _drafts.value.forEach { (conversationID, draft) ->
            canonicalDrafts[normalize(conversationID)] = draft
        }

        if (canonicalPinned != _pinned.value) {
            _pinned.value = canonicalPinned
            saveSet(PINNED_KEY, canonicalPinned)
        }
        if (canonicalMuted != _muted.value) {
            _muted.value = canonicalMuted
            saveSet(MUTED_KEY, canonicalMuted)
        }
        if (canonicalDrafts != _drafts.value) {
            val bounded = boundDrafts(canonicalDrafts)
            _drafts.value = bounded
            saveDrafts(bounded)
        }
    }

    fun clearInMemory() {
        _pinned.value = emptySet()
        _muted.value = emptySet()
        _drafts.value = emptyMap()
    }

    fun clearAll(): Boolean {
        val cleared = stateManager.clearSecureValuesSynchronously(
            PINNED_KEY,
            MUTED_KEY,
            DRAFTS_KEY
        )
        clearInMemory()
        return cleared
    }

    private fun loadSet(key: String): Set<String> = runCatching {
        val array = JSONArray(stateManager.getSecureValue(key) ?: return emptySet())
        buildSet {
            for (index in 0 until array.length()) add(normalize(array.getString(index)))
        }
    }.getOrDefault(emptySet())

    private fun saveSet(key: String, values: Set<String>) {
        stateManager.storeSecureValue(key, JSONArray(values.sorted()).toString())
    }

    private fun loadDrafts(): Map<String, String> = runCatching {
        val json = JSONObject(stateManager.getSecureValue(DRAFTS_KEY) ?: return emptyMap())
        val loaded = buildMap {
            json.keys().forEach { key ->
                json.optString(key).takeIf(String::isNotBlank)?.let {
                    put(normalize(key), it.take(MAX_DRAFT_CHARS))
                }
            }
        }
        boundDrafts(loaded)
    }.getOrDefault(emptyMap())

    private fun saveDrafts(values: Map<String, String>) {
        stateManager.storeSecureValue(
            DRAFTS_KEY,
            JSONObject().apply {
                values.forEach { (key, value) -> put(key, value) }
            }.toString()
        )
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    private fun normalize(value: String): String =
        canonicalize(value).lowercase()

    private fun boundDrafts(values: Map<String, String>): Map<String, String> {
        val retained = LinkedHashMap(values)
        while (
            retained.size > MAX_DRAFTS ||
            retained.values.sumOf(String::length) > MAX_DRAFT_CHARS_TOTAL
        ) {
            val oldest = retained.keys.firstOrNull() ?: break
            retained.remove(oldest)
        }
        return retained
    }

}
