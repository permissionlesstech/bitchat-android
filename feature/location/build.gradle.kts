plugins {
    id("bitchat.android.library.compose")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.feature.location"
}

// Location channels, location notes, the geohash picker activity and the globe,
// registered with the host as Nav3 entries through a Hilt multibinding.
dependencies {
    implementation(project(":feature:location:api"))
    implementation(project(":core:chat"))
    implementation(project(":core:designsystem:phone"))
    implementation(project(":core:mesh"))
    implementation(project(":core:nostr"))
    implementation(project(":core:tor"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.hilt.navigation.compose)
    // @Preview for the stateless screens; the renderer is debug-only.
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.gson)

    // The Compose BOM for androidTest comes from bitchat.android.library.compose.
    androidTestImplementation(libs.bundles.compose.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Compose's ui-test drags in espresso-core 3.5.0, which dies on API 35+;
    // see the same pin in app/build.gradle.kts.
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Declares the empty ComponentActivity that createComposeRule launches.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
