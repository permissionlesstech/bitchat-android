package com.bitchat.android.storage

import com.google.gson.Gson
import java.lang.reflect.Type

data class StorageMigration(
    val toVersion: Int,
    val migrate: (StorageRepository) -> Unit
)

class StorageRepository(
    val definition: StorageDefinition,
    private val store: KeyValueStore,
    private val gson: Gson = Gson()
) {

    fun getString(key: String, defaultValue: String? = null): String? = store.getString(key, defaultValue)
    fun putString(key: String, value: String?) = store.putString(key, value)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = store.getBoolean(key, defaultValue)
    fun putBoolean(key: String, value: Boolean) = store.putBoolean(key, value)
    fun getInt(key: String, defaultValue: Int): Int = store.getInt(key, defaultValue)
    fun putInt(key: String, value: Int) = store.putInt(key, value)
    fun getLong(key: String, defaultValue: Long): Long = store.getLong(key, defaultValue)
    fun putLong(key: String, value: Long) = store.putLong(key, value)
    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String> {
        return store.getStringSet(key, defaultValue)
    }
    fun putStringSet(key: String, value: Set<String>) = store.putStringSet(key, value)
    fun remove(key: String) = store.remove(key)
    fun contains(key: String): Boolean = store.contains(key)

    fun <T> getJson(key: String, type: Type): T? {
        val json = store.getString(key, null) ?: return null
        return runCatching { gson.fromJson<T>(json, type) }.getOrNull()
    }

    fun <T> getJson(key: String, clazz: Class<T>): T? {
        val json = store.getString(key, null) ?: return null
        return runCatching { gson.fromJson(json, clazz) }.getOrNull()
    }

    fun putJson(key: String, value: Any?) {
        if (value == null) {
            store.remove(key)
        } else {
            store.putString(key, gson.toJson(value))
        }
    }

    fun runMigrations(migrations: List<StorageMigration>) {
        var currentVersion = getInt(VERSION_KEY, 0)
        migrations
            .sortedBy { it.toVersion }
            .filter { it.toVersion > currentVersion }
            .forEach { migration ->
                migration.migrate(this)
                currentVersion = migration.toVersion
                putInt(VERSION_KEY, currentVersion)
            }
    }

    fun clearForPanic() {
        if (definition.panicClearPolicy != PanicClearPolicy.CLEAR_ON_PANIC) return

        when (definition.clearMode) {
            StorageClearMode.CLEAR_SCOPE -> store.clear()
            StorageClearMode.REMOVE_OWNED_KEYS -> definition.ownedKeys.forEach { store.remove(it) }
        }
    }

    companion object {
        private const val VERSION_KEY = "__storage_version"
    }
}
