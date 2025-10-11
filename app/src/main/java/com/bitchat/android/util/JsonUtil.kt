package com.bitchat.android.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.KSerializer

/**
 * JSON utility using kotlinx.serialization to replace Gson
 */
object JsonUtil {
    
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }
    
    /**
     * Serialize object to JSON string
     */
    inline fun <reified T> toJson(value: T): String {
        return json.encodeToString(value)
    }
    
    /**
     * Serialize object to JSON string with custom serializer
     */
    fun <T> toJson(serializer: KSerializer<T>, value: T): String {
        return json.encodeToString(serializer, value)
    }
    
    /**
     * Deserialize JSON string to object
     */
    inline fun <reified T> fromJson(jsonString: String): T {
        return json.decodeFromString(jsonString)
    }
    
    /**
     * Deserialize JSON string to object with custom serializer
     */
    fun <T> fromJson(serializer: KSerializer<T>, jsonString: String): T {
        return json.decodeFromString(serializer, jsonString)
    }
    
    /**
     * Safe deserialization that returns null on error
     */
    inline fun <reified T> fromJsonOrNull(jsonString: String): T? {
        return try {
            json.decodeFromString<T>(jsonString)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Safe deserialization with custom serializer that returns null on error
     */
    fun <T> fromJsonOrNull(serializer: KSerializer<T>, jsonString: String): T? {
        return try {
            json.decodeFromString(serializer, jsonString)
        } catch (e: Exception) {
            null
        }
    }
}