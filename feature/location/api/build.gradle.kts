plugins {
    id("bitchat.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bitchat.android.feature.location.api"
}

// What the rest of the app may know about settings: the location channels and location notes routes.
dependencies {
    api(project(":core:navigation"))
    // Aligns the Compose runtime that Navigation 3 brings with the rest of the app.
    implementation(platform(libs.androidx.compose.bom))
}
