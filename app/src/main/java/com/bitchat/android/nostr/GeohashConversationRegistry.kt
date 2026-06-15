package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.storage.PanicClearRegistry
import com.bitchat.android.storage.StorageDefinitions
import com.bitchat.android.storage.StorageMigration
import com.bitchat.android.storage.StorageModule
import com.bitchat.android.storage.StorageRepository
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

object GeohashConversationRegistry {
    private val map = ConcurrentHashMap<String, String>()
    private const val PREFS_NAME = "geohash_conversation_registry"
    private const val ENTRIES_KEY = "entries_v1"
    private var storage: StorageRepository? = null

    fun initialize(context: Context) {
        if (storage == null) {
            val legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            storage = StorageModule.repository(
                context = context,
                definition = StorageDefinitions.GeohashConversationRegistry,
                migrations = listOf(
                    StorageMigration(toVersion = 1) { repository ->
                        if (!repository.contains(ENTRIES_KEY)) {
                            val migrated = legacyPrefs.all
                                .mapNotNull { (key, value) -> if (value is String) key to value else null }
                                .toMap()
                            if (migrated.isNotEmpty()) {
                                repository.putJson(ENTRIES_KEY, migrated)
                                migrated.keys.forEach { repository.remove(it) }
                            }
                        }
                    }
                )
            )
            PanicClearRegistry.register(StorageDefinitions.GeohashConversationRegistry) { clear() }
            loadFromStorage()
        }
    }

    private fun loadFromStorage() {
        val repository = storage ?: return
        val type = object : TypeToken<Map<String, String>>() {}.type
        repository.getJson<Map<String, String>>(ENTRIES_KEY, type)?.let { entries ->
            map.clear()
            map.putAll(entries)
        }
    }

    private fun persist() {
        storage?.putJson(ENTRIES_KEY, map.toMap())
    }

    fun set(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            map[convKey] = geohash
            persist()
        }
    }

    fun get(convKey: String): String? = map[convKey]

    fun snapshot(): Map<String, String> = map.toMap()

    fun clear() {
        map.clear()
        storage?.clearForPanic()
    }
}
