plugins {
    id("bitchat.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bitchat.android.feature.share.api"
}

// What the rest of the app may know about APK sharing: the About section that
// renders it, the radio lease the hotspot needs, the build facts the app hands
// in, and the read-only distribution diagnostics. No project dependencies.
dependencies {
    // ShareAppSection is a @Composable taking a Modifier.
    api(libs.androidx.compose.ui)

    testImplementation(libs.bundles.testing)
}
