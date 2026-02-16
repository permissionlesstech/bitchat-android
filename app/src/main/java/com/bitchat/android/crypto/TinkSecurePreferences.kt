package com.bitchat.android.crypto

import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

class TinkSecurePreferences(
    private val prefs: SharedPreferences,
    private val aead: Aead
) {
    private val gson = Gson()

    fun putString(key: String, value: String) {
        val encoded = encryptStringForStorage(key, value)
        prefs.edit { putString(key, encoded) }
    }

    fun getString(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        val aad = key.toByteArray(Charsets.UTF_8)
        val plaintext = aead.decrypt(ciphertext, aad)
        return String(plaintext, Charsets.UTF_8)
    }

    fun putStringSet(key: String, values: Set<String>) {
        val encoded = encryptStringSetForStorage(key, values)
        prefs.edit { putString(key, encoded) }
    }

    fun getStringSet(key: String): Set<String>? {
        val json = getString(key) ?: return null
        val type = object : TypeToken<Set<String>>() {}.type
        return gson.fromJson(json, type)
    }

    fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun clear() {
        prefs.edit { clear() }
    }

    fun encryptStringForStorage(key: String, value: String): String {
        val aad = key.toByteArray(Charsets.UTF_8)
        val ciphertext = aead.encrypt(value.toByteArray(Charsets.UTF_8), aad)
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun encryptStringSetForStorage(key: String, values: Set<String>): String {
        val json = gson.toJson(values)
        return encryptStringForStorage(key, json)
    }
}
