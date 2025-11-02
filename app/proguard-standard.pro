# Standard flavor ProGuard rules
# These rules are applied ONLY to the standard flavor build

# Keep standard flavor Tor implementation
-keep class com.bitchat.android.net.StandardTorProvider { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# No Tor-related rules needed for standard build
# (Guardian Project Arti library not included)
