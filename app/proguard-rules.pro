# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.bitchat.android.protocol.** { *; }
-keep class com.bitchat.android.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Keep SecureIdentityStateManager from being obfuscated to prevent reflection issues
-keep class com.bitchat.android.identity.SecureIdentityStateManager {
    private android.content.SharedPreferences prefs;
    *;
}

# Keep all classes that might use reflection
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.nostr.** { *; }
-keep class com.bitchat.android.identity.** { *; }

# Keep TorProvider implementations (flavor-specific)
-keep class com.bitchat.android.net.TorProvider { *; }
-keep class com.bitchat.android.net.StandardTorProvider { *; }
-keep class com.bitchat.android.net.RealTorProvider { *; }
-keep class com.bitchat.android.net.TorProviderFactory { *; }

# Note: Tor-specific ProGuard rules have been moved to proguard-tor.pro
# (applied only to tor flavor builds)
