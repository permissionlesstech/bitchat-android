package com.bitchat.android.util

import android.content.Context
import androidx.core.content.edit
import com.bitchat.android.net.OkHttpProvider

/** Persistent, route-specific cooldowns for APK-related network requests. */
internal class ApkRateLimitStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "apk_network_cooldowns"
        private const val FALLBACK_COOLDOWN_MILLIS = 60_000L
        private const val MAX_COOLDOWN_MILLIS = 60 * 60_000L
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun retryAtMillis(
        scope: String,
        route: OkHttpProvider.Route,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val key = key(scope, route)
        val deadline = preferences.getLong(key, 0L)
        if (deadline <= nowMillis) {
            if (deadline != 0L) preferences.edit { remove(key) }
            return null
        }
        return deadline
    }

    fun recordRateLimit(
        scope: String,
        route: OkHttpProvider.Route,
        serverRetryAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val fallback = nowMillis + FALLBACK_COOLDOWN_MILLIS
        val maximum = nowMillis + MAX_COOLDOWN_MILLIS
        val deadline = (serverRetryAtMillis ?: fallback).coerceIn(nowMillis + 1_000L, maximum)
        // Persist before reporting the failure so a process restart cannot bypass the cooldown.
        preferences.edit(commit = true) { putLong(key(scope, route), deadline) }
        return deadline
    }

    fun clear(scope: String, route: OkHttpProvider.Route) {
        preferences.edit { remove(key(scope, route)) }
    }

    fun blockedException(
        source: ApkDownloadSource,
        retryAtMillis: Long
    ): ApkDownloadException {
        return ApkDownloadException(
            message = "${source.id} is in a persisted rate-limit cooldown until $retryAtMillis",
            reason = ApkDownloadFailureReason.RateLimited,
            messageArgs = listOf(source.displayName),
            retryable = false,
            sourceId = source.id,
            retryAtMillis = retryAtMillis
        )
    }

    private fun key(scope: String, route: OkHttpProvider.Route): String =
        "${scope}_${route.name.lowercase()}"
}
