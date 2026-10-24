plugins {
    id("bitchat.android.library.compose")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.feature.share"
}

// APK sharing: downloading the verified universal APK (WorkManager), serving it
// over a local hotspot (NanoHTTPD) and the hotspot screen. The app binds the
// ports in :feature:share:api (the hotspot radio lease and its build facts).
dependencies {
    implementation(project(":feature:share:api"))
    implementation(project(":core:designsystem:phone"))
    implementation(project(":core:tor"))
    // The notification icon the download worker shares with the chat notifications.
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.zxing.core)

    // WorkManager for background APK downloads
    implementation(libs.androidx.work.runtime.ktx)

    // HTTP server for hotspot APK sharing
    implementation(libs.nanohttpd)

    testImplementation(libs.bundles.testing)
}
