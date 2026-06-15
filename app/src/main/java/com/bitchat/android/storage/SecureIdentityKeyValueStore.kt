package com.bitchat.android.storage

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager

class SecureIdentityKeyValueStore(
    context: Context
) : KeyValueStore {

    private val secure = SecureIdentityStateManager(context.applicationContext)

    override fun getString(key: String, defaultValue: String?): String? {
        return secure.getSecureValue(key) ?: defaultValue
    }

    override fun putString(key: String, value: String?) {
        if (value == null) {
            secure.removeSecureValue(key)
        } else {
            secure.storeSecureValue(key, value)
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return getString(key)?.toBooleanStrictOrNull() ?: defaultValue
    }

    override fun putBoolean(key: String, value: Boolean) {
        putString(key, value.toString())
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return getString(key)?.toIntOrNull() ?: defaultValue
    }

    override fun putInt(key: String, value: Int) {
        putString(key, value.toString())
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return getString(key)?.toLongOrNull() ?: defaultValue
    }

    override fun putLong(key: String, value: Long) {
        putString(key, value.toString())
    }

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> {
        return getString(key)?.split(STRING_SET_SEPARATOR)?.filter { it.isNotEmpty() }?.toSet()
            ?: defaultValue
    }

    override fun putStringSet(key: String, value: Set<String>) {
        putString(key, value.joinToString(STRING_SET_SEPARATOR))
    }

    override fun contains(key: String): Boolean {
        return secure.hasSecureValue(key)
    }

    override fun remove(key: String) {
        secure.removeSecureValue(key)
    }

    override fun clear() {
        secure.clearIdentityData()
    }

    private companion object {
        private const val STRING_SET_SEPARATOR = "\u001F"
    }
}
