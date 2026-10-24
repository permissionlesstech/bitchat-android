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
// because the native symbol names are derived from them. A leaf: no project
// dependencies. Route changes go out on ArtiTorManager.routeChanges.
dependencies {
    // OkHttpProvider hands out OkHttp clients.
    api(libs.okhttp)
    // TorPreferenceManager and ArtiTorManager expose StateFlow.
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.bundles.testing)
}
