package com.bitchat.android.panic

/**
 * A preferences file the wipe keeps, but only by key: every key that is neither in [keys] nor
 * starts with one of [keyPrefixes] is removed.
 */
data class KeptPrefs(
    val file: String,
    val keys: Set<String>,
    val keyPrefixes: Set<String> = emptySet(),
    val reason: String,
) {
    fun keeps(key: String): Boolean = key in keys || keyPrefixes.any { key.startsWith(it) }
}

/**
 * A path the wipe keeps, relative to the app's data directory. A directory keeps its whole
 * subtree; a [relative] ending in `*` keeps every sibling whose name starts with the text before it.
 */
data class KeptPath(val relative: String, val reason: String)

/**
 * Everything panic leaves behind. Anything not listed here is wiped, so a new file, database or
 * `getDir` folder is wiped by default. Each key is copied from its owner's constant.
 */
object KeepList {
    private const val SETTINGS = "appearance, network and onboarding choices"

    val prefs: List<KeptPrefs> = listOf(
        KeptPrefs(
            file = "bitchat_settings",
            keys = setOf(
                "chat_ui_mode", // ChatUiMode KEY_CHAT_UI_MODE
                "theme_preference", // ThemePreference KEY_THEME
                "tor_mode", // TorPreferenceManager KEY_TOR_MODE
                "battery_optimization_skipped", // BatteryOptimizationPreferenceManager KEY_BATTERY_SKIP
                "background_location_skipped", // BackgroundLocationPreferenceManager KEY_BACKGROUND_LOCATION_SKIP
                "ptt.liveVoiceEnabled", // LiveVoicePreferences ENABLED
            ),
            reason = SETTINGS,
        ),
        KeptPrefs(
            file = "bitchat_permissions",
            keys = setOf("first_time_onboarding_complete"), // DeviceReadiness KEY_FIRST_TIME_COMPLETE
            // DeviceReadiness KEY_OPTIONAL_PERMISSION_REQUESTED_PREFIX
            keyPrefixes = setOf("optional_permission_requested_"),
            reason = "onboarding progress",
        ),
        KeptPrefs(
            file = "bitchat_mesh_service_prefs",
            keys = setOf(
                "auto_start_on_boot", // MeshServicePreferences KEY_AUTO_START
                "background_enabled", // MeshServicePreferences KEY_BACKGROUND_ENABLED
            ),
            reason = "background service choice",
        ),
        KeptPrefs(
            file = "spam_filter_preferences",
            keys = setOf(
                "spam_filter_enabled", // SpamFilterPreferences KEY_ENABLED
                "spam_filter_debug_bypass", // KEY_DEBUG_BYPASS
                "spam_filter_message_burst", // KEY_MESSAGE_BURST
                "spam_filter_seconds_per_message", // KEY_SECONDS_PER_MESSAGE
                "spam_filter_characters_per_minute", // KEY_CHARACTERS_PER_MINUTE
                "spam_filter_links_per_minute", // KEY_LINKS_PER_MINUTE
                "spam_filter_allowed_repeats", // KEY_ALLOWED_REPEATS
                "spam_filter_cross_user_allowed_repeats", // KEY_CROSS_USER_ALLOWED_REPEATS
                "spam_filter_cross_user_min_length", // KEY_CROSS_USER_MIN_LENGTH
                "spam_filter_repeat_similarity", // KEY_REPEAT_SIMILARITY
                "spam_filter_repeat_window_minutes", // KEY_REPEAT_WINDOW_MINUTES
                "spam_filter_self_match_threshold", // KEY_SELF_MATCH_THRESHOLD
                "spam_filter_self_match_min_length", // KEY_SELF_MATCH_MIN_LENGTH
            ),
            reason = "spam filter tuning",
        ),
        KeptPrefs(
            file = "pow_preferences",
            keys = setOf(
                "pow_enabled", // PoWPreferenceManager KEY_POW_ENABLED
                "pow_difficulty", // PoWPreferenceManager KEY_POW_DIFFICULTY
            ),
            reason = "proof-of-work choice",
        ),
        KeptPrefs(
            file = "bitchat_debug_settings",
            keys = setOf(
                "verbose_logging", // DebugPreferenceManager KEY_VERBOSE
                "gatt_server_enabled", // KEY_GATT_SERVER
                "gatt_client_enabled", // KEY_GATT_CLIENT
                "packet_relay_enabled", // KEY_PACKET_RELAY
                "max_connections_overall", // KEY_MAX_CONN_OVERALL
                "max_connections_server", // KEY_MAX_CONN_SERVER
                "max_connections_client", // KEY_MAX_CONN_CLIENT
                "seen_packet_capacity", // KEY_SEEN_PACKET_CAP
                "gcs_max_filter_bytes", // KEY_GCS_MAX_BYTES
                "gcs_filter_fpr_percent", // KEY_GCS_FPR
                "ble_enabled", // KEY_BLE_ENABLED
                "wifi_aware_enabled", // KEY_WIFI_AWARE_ENABLED
                "wifi_aware_verbose", // KEY_WIFI_AWARE_VERBOSE
            ),
            reason = "debug and transport toggles",
        ),
        KeptPrefs(
            file = "relay_directory_prefs",
            keys = setOf("last_update_ms"), // RelayDirectory KEY_LAST_UPDATE_MS
            reason = "public relay list refresh time",
        ),
        KeptPrefs(
            file = "apk_release_metadata",
            keys = setOf(
                // GitHubReleaseClient cache entry
                "version", "size", "url", "name", "etag", "fetched_at",
            ),
            reason = "public release metadata",
        ),
        KeptPrefs(
            file = "apk_network_cooldowns",
            keys = emptySet(),
            // ApkRateLimitStore key(scope, route) is "<scope>_<route>": GitHubReleaseClient
            // RATE_LIMIT_SCOPE, and UniversalApkManager's per-source "apk_asset_<id>" scope. The
            // first prefix stops before the underscore so that it starts a literal in the sources.
            keyPrefixes = setOf("github_release_metadata", "apk_asset_"),
            reason = "rate-limit protection",
        ),
    )

    private val kept: List<KeptPath> = listOf(
        KeptPath("files/arti", "Tor route state"),
        KeptPath("files/nostr_relays_latest.csv", "public relay list"),
        // RelayDirectory downloads into a "relays_" temp file before it replaces the list, on a
        // process-wide refresh that a panic does not stop.
        KeptPath("cache/relays_*", "public relay list download"),
        KeptPath("cache/universal_apk", "public release APK"),
        KeptPath("no_backup/androidx.work.workdb*", "APK download jobs only"),
        KeptPath("databases/androidx.work.workdb*", "APK download jobs only"),
        KeptPath("files/profileInstalled", "ART profile marker"),
        KeptPath("files/profileinstaller_*", "ART profile markers"),
        KeptPath("no_backup/panic_pending", "the panic marker"),
        KeptPath("no_backup/saved_screens_stale", "a flag that saved screen state predates a wipe"),
    )

    val paths: List<KeptPath> = kept + prefs.map {
        KeptPath("shared_prefs/${it.file}.xml", "kept preferences, by key")
    }
}
