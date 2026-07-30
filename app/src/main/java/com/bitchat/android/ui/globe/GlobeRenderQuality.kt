package com.bitchat.android.ui.globe

import android.content.Context

enum class GlobeRenderQuality {
    FAST,
    MEDIUM,
    HIGH;

    companion object {
        fun fromStoredValue(value: String?): GlobeRenderQuality =
            entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

object GlobeRenderQualityPreference {
    private const val PREFERENCES_NAME = "bitchat_settings"
    private const val KEY_RENDER_QUALITY = "geohash_globe_render_quality"

    fun load(context: Context): GlobeRenderQuality {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return GlobeRenderQuality.fromStoredValue(
            preferences.getString(KEY_RENDER_QUALITY, GlobeRenderQuality.MEDIUM.name)
        )
    }

    fun save(context: Context, quality: GlobeRenderQuality) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RENDER_QUALITY, quality.name)
            .apply()
    }
}
