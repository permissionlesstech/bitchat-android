plugins {
    id("bitchat.android.library")
}

android {
    namespace = "com.bitchat.android.core.nostr"
}

// Nostr relays, gift-wrapped private messages, geohash channels and location. It knows nothing
// of the chat screens: private messages land in a PrivateMessageInbox the chat session
// implements, and HTTP clients come from a NostrHttpClients the app installs, so relay traffic
// follows the app's Tor policy.
dependencies {
    api(project(":core:mesh"))
    // NostrHttpClients hands out OkHttp clients.
    api(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.cryptography)
    implementation(libs.gson)
    implementation(libs.gms.location)

    testImplementation(testFixtures(project(":core:mesh")))
    testImplementation(libs.bundles.testing)
}
