plugins {
    id("bitchat.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bitchat.android.feature.chat.api"
}

// What the rest of the app may know about chat: its six routes, and how to open
// a private chat over the right destination.
dependencies {
    api(project(":core:navigation"))
    // Aligns the Compose runtime that Navigation 3 brings with the rest of the app.
    implementation(platform(libs.androidx.compose.bom))

    testImplementation(libs.bundles.testing)
}
