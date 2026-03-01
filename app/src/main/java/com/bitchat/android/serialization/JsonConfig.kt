package com.bitchat.android.serialization

import kotlinx.serialization.json.Json

object JsonConfig {
    val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}
