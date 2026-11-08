plugins {
    id("bitchat.android.library")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.core.chat"
}

// The chat session behind the chat screens: its state and managers, private conversations,
// notifications and QR verification, in the Activity-retained scope. The app supplies what is
// its own through ports it binds: the notification targets, the mesh to restart after a panic
// clear, the Wi-Fi Aware peers.
dependencies {
    api(project(":core:nostr"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.gson)

    testImplementation(testFixtures(project(":core:mesh")))
    testImplementation(libs.bundles.testing)
}
