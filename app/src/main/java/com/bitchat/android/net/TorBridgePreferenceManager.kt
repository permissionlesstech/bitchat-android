package com.bitchat.android.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Stores user-specified Arti bridge settings.
 * We persist:
 * - enabled flag
 * - list of bridge lines (strings understood by Arti)
 *
 * Bridge line formats accepted (we store normalized lines):
 *  - "Bridge 192.0.2.66:443 FINGERPRINT"
 *  - "Bridge obfs4 192.0.2.77:443 FPR cert=... iat-mode=..."
 *  - "obfs4 192.0.2.77:443 FPR cert=... iat-mode=..." (we prefix "Bridge ")
 *  - "192.0.2.66:443 FINGERPRINT" (we prefix "Bridge ")
 */
object TorBridgePreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_TOR_BRIDGES_ENABLED = "tor_bridges_enabled"
    private const val KEY_TOR_BRIDGES_LINES = "tor_bridges_lines_json"

    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow

    private val _linesFlow = MutableStateFlow<List<String>>(emptyList())
    val linesFlow: StateFlow<List<String>> = _linesFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_TOR_BRIDGES_ENABLED, false)
        val linesJson = prefs.getString(KEY_TOR_BRIDGES_LINES, null)
        _enabledFlow.value = enabled
        _linesFlow.value = parseJsonLines(linesJson)
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TOR_BRIDGES_ENABLED, false)
    }

    fun getLines(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return parseJsonLines(prefs.getString(KEY_TOR_BRIDGES_LINES, null))
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_TOR_BRIDGES_ENABLED, enabled).apply()
        _enabledFlow.value = enabled
    }

    fun setLines(context: Context, lines: List<String>) {
        val normalized = lines.mapNotNull { normalizeBridgeLine(it) }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TOR_BRIDGES_LINES, JSONArray(normalized).toString()).apply()
        _linesFlow.value = normalized
    }

    fun addLine(context: Context, line: String) {
        val normalized = normalizeBridgeLine(line) ?: return
        val current = getLines(context).toMutableList()
        if (!current.contains(normalized)) {
            current.add(normalized)
            setLines(context, current)
        }
    }

    fun removeLine(context: Context, line: String) {
        val current = getLines(context).toMutableList()
        current.remove(line)
        setLines(context, current)
    }

    private fun parseJsonLines(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }.mapNotNull { normalizeBridgeLine(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Normalize a user-supplied bridge line to a format Arti accepts.
     * If the line already starts with "Bridge ", keep as-is (trimmed).
     * If it starts with a transport like "obfs4 ", prefix with "Bridge ".
     * If it looks like "IP:port fingerprint...", prefix with "Bridge ".
     */
    fun normalizeBridgeLine(raw: String?): String? {
        val s = raw?.trim()?.replace("\\s+".toRegex(), " ") ?: return null
        if (s.isEmpty()) return null
        return when {
            s.startsWith("Bridge ", ignoreCase = true) -> s
            s.startsWith("obfs4 ", ignoreCase = true) -> "Bridge $s"
            // Allow meek, scramblesuit, etc. future-proof: if first token has a colon it's likely host:port then fingerprint
            s.substringBefore(' ').contains(":") -> "Bridge $s"
            else -> s // fallback: keep
        }
    }
}
