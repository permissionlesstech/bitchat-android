# Tor flavor ProGuard rules
# These rules are applied ONLY to the tor flavor build

# Arti (Guardian Project Tor implementation in Rust) ProGuard rules
-keep class info.guardianproject.arti.** { *; }
-keep class org.torproject.jni.** { *; }
-keepnames class org.torproject.jni.**
-dontwarn info.guardianproject.arti.**
-dontwarn org.torproject.jni.**

# Keep Tor-specific classes
-keep class com.bitchat.android.net.RealTorProvider { *; }

# Preserve line numbers for debugging Tor-related crashes
-keepattributes SourceFile,LineNumberTable
