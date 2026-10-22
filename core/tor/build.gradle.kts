plugins {
    id("bitchat.android.library")
}

android {
    namespace = "com.bitchat.android.core.tor"
}

// Arti (Tor in Rust), its JNI bridge and the OkHttp provider every HTTP and
// WebSocket client is built from. The four committed libarti_android.so files
// under src/main/jniLibs are pinned by tools/arti-build/SHA256SUMS. The JNI
// classes keep their org.torproject.arti and info.guardianproject.arti packages
// because the native symbol names are derived from them.
dependencies {
    // OkHttpProvider hands out OkHttp clients.
    api(libs.okhttp)
    // TorPreferenceManager and ArtiTorManager expose StateFlow.
    api(libs.kotlinx.coroutines.android)
    // ArtiTorManager resets the Nostr relay connections whenever the Tor route
    // changes, so no relay socket outlives the route it was opened on. :core:nostr
    // brings :core:mesh, whose AppConstants holds the Tor timings.
    implementation(project(":core:nostr"))

    testImplementation(libs.bundles.testing)
}
