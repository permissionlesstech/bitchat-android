package com.bitchat.android.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.crypto.TinkAeadProvider
import com.bitchat.android.crypto.TinkSecurePreferences
import java.io.IOException
import java.security.GeneralSecurityException
import androidx.core.content.edit

object DataMigrationManager {
    private const val TAG = "DataMigrationManager"
    private const val OLD_PREFS_NAME = "bitchat_identity"
    private const val NEW_PREFS_NAME = "bitchat_identity_tink"
    private const val MIGRATION_PREFS = "bitchat_migrations"
    private const val MIGRATION_FLAG = "tink_migration_complete"

    fun migrateIfNeeded(context: Context) {
        val markerPrefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (markerPrefs.getBoolean(MIGRATION_FLAG, false)) return

        try {
            val oldPrefs = openOldEncryptedPrefs(context)
            if (oldPrefs.all.isEmpty()) {
                markerPrefs.edit { putBoolean(MIGRATION_FLAG, true) }
                return
            }

            val aead = TinkAeadProvider.getAead(context)
            val newPrefs = context.getSharedPreferences(NEW_PREFS_NAME, Context.MODE_PRIVATE)
            val tinkPrefs = TinkSecurePreferences(newPrefs, aead)

            for ((key, value) in oldPrefs.all) {
                when (value) {
                    is String -> tinkPrefs.putString(key, value)
                    is Set<*> -> {
                        val set = value.filterIsInstance<String>().toSet()
                        tinkPrefs.putStringSet(key, set)
                    }
                    else -> Log.w(TAG, "Skipping unsupported type for key: $key")
                }
            }

            context.deleteSharedPreferences(OLD_PREFS_NAME)
            markerPrefs.edit { putBoolean(MIGRATION_FLAG, true) }
            Log.i(TAG, "EncryptedSharedPreferences migrated to Tink")
        } catch (e: Exception) {
            handleMigrationFailure(context, e)
        }
    }

    private fun openOldEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            OLD_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun handleMigrationFailure(context: Context, error: Exception) {
        Log.e(TAG, "Migration failed: ${error.message}", error)
        when (error) {
            is GeneralSecurityException,
            is IOException -> {
                context.deleteSharedPreferences(OLD_PREFS_NAME)
            }
            else -> Unit
        }
    }
}
