plugins {
    id("bitchat.android.library")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.core.chat"
}

// The chat session behind the chat screens: its state and managers, private conversations,
// notifications and QR verification, in a scope inside the identity. The app supplies what is
// its own through ports it binds: the notification targets, the identity's start-up, the
// Wi-Fi Aware peers.
dependencies {
    api(project(":core:nostr"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.gson)

    testImplementation(testFixtures(project(":core:mesh")))
    testImplementation(libs.bundles.testing)
}
