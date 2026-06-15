package com.bitchat.android.storage

data class PanicClearEntry(
    val definition: StorageDefinition,
    val clear: () -> Unit
)

data class PanicClearResult(
    val id: String,
    val owner: String,
    val success: Boolean,
    val errorMessage: String? = null
)

object PanicClearRegistry {
    private val entries = linkedMapOf<String, PanicClearEntry>()

    @Synchronized
    fun register(definition: StorageDefinition, clear: () -> Unit) {
        if (definition.panicClearPolicy == PanicClearPolicy.CLEAR_ON_PANIC) {
            entries[definition.id] = PanicClearEntry(definition, clear)
        }
    }

    @Synchronized
    fun registerIfAbsent(definition: StorageDefinition, clear: () -> Unit) {
        if (definition.panicClearPolicy == PanicClearPolicy.CLEAR_ON_PANIC && !entries.containsKey(definition.id)) {
            entries[definition.id] = PanicClearEntry(definition, clear)
        }
    }

    @Synchronized
    fun registeredEntries(): List<PanicClearEntry> = entries.values.toList()

    fun clearAll(): List<PanicClearResult> {
        val snapshot = synchronized(this) { entries.values.toList() }
        return snapshot.map { entry ->
            try {
                entry.clear()
                PanicClearResult(
                    id = entry.definition.id,
                    owner = entry.definition.owner,
                    success = true
                )
            } catch (e: Exception) {
                PanicClearResult(
                    id = entry.definition.id,
                    owner = entry.definition.owner,
                    success = false,
                    errorMessage = e.message
                )
            }
        }
    }

    @Synchronized
    fun resetForTesting() {
        entries.clear()
    }
}
