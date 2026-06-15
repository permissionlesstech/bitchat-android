package com.bitchat.android.storage

import android.content.Context
import com.google.gson.Gson

object StorageModule {
    private val gson = Gson()

    fun repository(
        context: Context,
        definition: StorageDefinition,
        migrations: List<StorageMigration> = emptyList()
    ): StorageRepository {
        val appContext = context.applicationContext
        val store = when (definition.security) {
            StorageSecurity.NORMAL -> SharedPreferencesKeyValueStore(
                appContext.getSharedPreferences(definition.prefsName, Context.MODE_PRIVATE)
            )
            StorageSecurity.SECURE -> SecureIdentityKeyValueStore(appContext)
        }

        val repository = StorageRepository(definition, store, gson)
        repository.runMigrations(migrations)
        PanicClearRegistry.registerIfAbsent(definition) { repository.clearForPanic() }
        return repository
    }

    fun registerKnownPanicStores(context: Context) {
        StorageDefinitions.panicClearDefinitions.forEach { definition ->
            repository(context, definition)
        }
    }
}
