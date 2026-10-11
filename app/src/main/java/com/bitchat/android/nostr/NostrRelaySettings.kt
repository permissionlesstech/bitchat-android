package com.bitchat.android.nostr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Relays someone has added by hand, alongside the built-in set.
 *
 * The built-in relays are four well-known clearnet hostnames, so a censor
 * blocking four names ends internet-delivered private messages for everyone.
 * Adding relays — including `.onion` addresses, or a relay run by whoever
 * needs it — is the escape hatch that does not require shipping a new build.
 *
 * Stored normalized so comparisons against connection keys and the built-in
 * set are exact, and bounded so a long list cannot turn every send into a
 * fan-out across dozens of sockets.
 *
 * Port of iOS `NostrRelaySettings`.
 */
object NostrRelaySettings {
    /** Enough for a personal relay, an onion, and a couple of regional fallbacks. */
    const val maxCustomRelays = 8

    private const val PREFS_NAME = "nostr_relay_settings"
    private const val KEY_CUSTOM_RELAYS = "nostr.customRelays"

    sealed class AddFailure {
        data object Malformed : AddFailure()
        data object AlreadyPresent : AddFailure()
        data object LimitReached : AddFailure()
    }

    sealed class AddResult {
        data class Success(val url: String) : AddResult()
        data class Failure(val reason: AddFailure) : AddResult()
    }

    private val _customRelays = MutableStateFlow<List<String>>(emptyList())
    val customRelaysFlow: StateFlow<List<String>> = _customRelays.asStateFlow()

    private var sharedPrefs: SharedPreferences? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        sharedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _customRelays.value = readStored()
        isInitialized = true
    }

    /** Normalized relay URLs, in the order they were added. */
    fun customRelays(): List<String> {
        if (!isInitialized) return emptyList()
        return _customRelays.value
    }

    fun add(rawValue: String, builtIn: Set<String>): AddResult {
        val normalized = NostrRelayURL.normalized(rawValue, defaultScheme = "wss")
            ?: return AddResult.Failure(AddFailure.Malformed)

        val current = customRelays().toMutableList()
        if (current.contains(normalized) || builtIn.contains(normalized)) {
            return AddResult.Failure(AddFailure.AlreadyPresent)
        }
        if (current.size >= maxCustomRelays) {
            return AddResult.Failure(AddFailure.LimitReached)
        }

        current.add(normalized)
        write(current)
        return AddResult.Success(normalized)
    }

    fun remove(url: String) {
        val normalized = NostrRelayURL.normalized(url, defaultScheme = "wss") ?: return
        write(customRelays().filter { it != normalized })
    }

    /**
     * Panic-wipe hook: an added relay names somewhere someone chose to route
     * through, which is exactly the kind of trace a wipe should not leave.
     */
    fun reset() {
        sharedPrefs?.edit()?.remove(KEY_CUSTOM_RELAYS)?.apply()
        _customRelays.value = emptyList()
    }

    /** Test / panic helper: re-bind prefs under a fresh name. */
    internal fun resetForTests() {
        sharedPrefs = null
        isInitialized = false
        _customRelays.value = emptyList()
    }

    private fun readStored(): List<String> {
        val prefs = sharedPrefs ?: return emptyList()
        val raw = prefs.getString(KEY_CUSTOM_RELAYS, null) ?: return emptyList()
        val seen = linkedSetOf<String>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val normalized = NostrRelayURL.normalized(array.optString(i), defaultScheme = "wss")
                if (normalized != null) seen.add(normalized)
            }
        } catch (_: Exception) {
            // Older / hand-edited values: treat as newline-separated.
            raw.split('\n').mapNotNull { NostrRelayURL.normalized(it, defaultScheme = "wss") }
                .forEach { seen.add(it) }
        }
        return seen.toList()
    }

    private fun write(relays: List<String>) {
        val array = JSONArray()
        relays.forEach { array.put(it) }
        sharedPrefs?.edit()?.putString(KEY_CUSTOM_RELAYS, array.toString())?.apply()
        _customRelays.value = relays
    }
}
