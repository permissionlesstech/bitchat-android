plugins {
    id("bitchat.android.library")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.bitchat.android.core.mesh"

    // Test doubles that :app's tests need too, such as an in-memory conversation store cipher.
    testFixtures {
        enable = true
    }
}

// The Bluetooth mesh transport stack shared by the phone and the watch: packet protocol,
// Noise sessions, identity, gossip sync, private conversation storage and voice frames.
// Each app supplies its own policy where the two differ (Bluetooth permissions, the debug
// transport toggles), so nothing here knows which client it runs in.
dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    // The same BouncyCastle and Tink the phone pins; Tink backs the encrypted identity prefs.
    implementation(libs.bundles.cryptography)
    implementation(libs.gson)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.bundles.testing)
}
