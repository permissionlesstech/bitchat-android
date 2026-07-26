package com.bitchat.android.services.bridge

import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
 * A small insertion-ordered expiring set. Callers own synchronization; bridge
 * coordinators keep each instance confined to their serial dispatcher.
 */
internal class PersistentExpiringIdSet(
    private val preferences: SharedPreferences,
    private val key: String,
    private val capacity: Int
) {
    private val gson = Gson()
    private val values: LinkedHashMap<String, Long> = load()

    fun contains(id: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        return (values[id] ?: return false) > nowMs
    }

    fun add(id: String, lifetimeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        prune(nowMs)
        values.remove(id)
        values[id] = nowMs + lifetimeMs
        while (values.size > capacity) values.remove(values.keys.first())
        persist()
    }

    fun clear() {
        values.clear()
        preferences.edit { remove(key) }
    }

    private fun prune(nowMs: Long) {
        val changed = values.entries.removeAll { it.value <= nowMs }
        if (changed) persist()
    }

    private fun load(): LinkedHashMap<String, Long> {
        val type = object : TypeToken<Map<String, Long>>() {}.type
        val decoded: Map<String, Long> = runCatching {
            preferences.getString(key, null)
                ?.let { json -> gson.fromJson<Map<String, Long>>(json, type) }
        }.getOrNull() ?: emptyMap()
        return LinkedHashMap(decoded)
    }

    private fun persist() {
        preferences.edit { putString(key, gson.toJson(values)) }
    }
}
