package com.bitchat.android.storage

enum class StorageSecurity {
    NORMAL,
    SECURE
}

enum class PanicClearPolicy {
    CLEAR_ON_PANIC,
    KEEP_ON_PANIC
}

enum class StorageClearMode {
    CLEAR_SCOPE,
    REMOVE_OWNED_KEYS
}

data class StorageDefinition(
    val id: String,
    val owner: String,
    val prefsName: String,
    val security: StorageSecurity,
    val panicClearPolicy: PanicClearPolicy,
    val clearMode: StorageClearMode = StorageClearMode.CLEAR_SCOPE,
    val ownedKeys: Set<String> = emptySet()
)

object StorageDefinitions {
    val Chat = StorageDefinition(
        id = "chat",
        owner = "DataManager",
        prefsName = "bitchat_prefs",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.CLEAR_ON_PANIC
    )

    val GeohashBookmarks = StorageDefinition(
        id = "geohash_bookmarks",
        owner = "GeohashBookmarksStore",
        prefsName = "geohash_prefs",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.CLEAR_ON_PANIC
    )

    val GeohashAliasRegistry = StorageDefinition(
        id = "geohash_alias_registry",
        owner = "GeohashAliasRegistry",
        prefsName = "geohash_alias_registry",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.CLEAR_ON_PANIC
    )

    val GeohashConversationRegistry = StorageDefinition(
        id = "geohash_conversation_registry",
        owner = "GeohashConversationRegistry",
        prefsName = "geohash_conversation_registry",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.CLEAR_ON_PANIC
    )

    val Favorites = StorageDefinition(
        id = "favorites",
        owner = "FavoritesPersistenceService",
        prefsName = "bitchat_identity",
        security = StorageSecurity.SECURE,
        panicClearPolicy = PanicClearPolicy.CLEAR_ON_PANIC,
        clearMode = StorageClearMode.REMOVE_OWNED_KEYS,
        ownedKeys = setOf("favorite_relationships", "favorite_peerid_index")
    )

    val SeenMessages = StorageDefinition(
        id = "seen_messages",
        owner = "SeenMessageStore",
        prefsName = "bitchat_identity",
        security = StorageSecurity.SECURE,
        panicClearPolicy = PanicClearPolicy.CLEAR_ON_PANIC,
        clearMode = StorageClearMode.REMOVE_OWNED_KEYS,
        ownedKeys = setOf("seen_message_store_v1")
    )

    val MeshServicePreferences = StorageDefinition(
        id = "mesh_service_preferences",
        owner = "MeshServicePreferences",
        prefsName = "bitchat_mesh_service_prefs",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.KEEP_ON_PANIC
    )

    val DebugPreferences = StorageDefinition(
        id = "debug_preferences",
        owner = "DebugPreferenceManager",
        prefsName = "bitchat_debug_settings",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.KEEP_ON_PANIC
    )

    val PowPreferences = StorageDefinition(
        id = "pow_preferences",
        owner = "PoWPreferenceManager",
        prefsName = "pow_preferences",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.KEEP_ON_PANIC
    )

    val AppSettings = StorageDefinition(
        id = "app_settings",
        owner = "TorPreferenceManager, ThemePreferenceManager, and onboarding preference managers",
        prefsName = "bitchat_settings",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.KEEP_ON_PANIC
    )

    val OnboardingPermissions = StorageDefinition(
        id = "onboarding_permissions",
        owner = "PermissionManager",
        prefsName = "bitchat_permissions",
        security = StorageSecurity.NORMAL,
        panicClearPolicy = PanicClearPolicy.KEEP_ON_PANIC
    )

    val panicClearDefinitions = listOf(
        Chat,
        GeohashBookmarks,
        GeohashAliasRegistry,
        GeohashConversationRegistry,
        Favorites,
        SeenMessages
    )
}
