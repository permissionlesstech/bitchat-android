package com.bitchat.android.hotspot

/**
 * Stable failures produced by hotspot lifecycle code.
 *
 * The manager reports meaning, not presentation text. The UI layer owns localization.
 */
enum class HotspotError {
    P2P_UNSUPPORTED,
    LOCAL_NETWORK_PERMISSION_REQUIRED,
    NEARBY_WIFI_PERMISSION_REQUIRED,
    PREPARATION_FAILED,
    PERMISSION_REVOKED,
    P2P_DISABLED,
    FOREIGN_GROUP_ACTIVE,
    P2P_BUSY,
    START_FAILED,
    PREFLIGHT_TIMEOUT,
    STALE_GROUP_REMOVAL_FAILED,
    GROUP_LOST,
    P2P_SERVICE_DISCONNECTED,
    CONNECTION_INFO_UNAVAILABLE,
    WEB_SERVER_START_FAILED,
    UNKNOWN
}
