plugins {
    id("bitchat.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bitchat.android.feature.settings.api"
}

// What the rest of the app may know about settings: the About and Debug routes.
dependencies {
    api(project(":core:navigation"))
    // Aligns the Compose runtime that Navigation 3 brings with the rest of the app.
    implementation(platform(libs.androidx.compose.bom))
}
