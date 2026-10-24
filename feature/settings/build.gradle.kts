plugins {
    id("bitchat.android.library.compose")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.feature.settings"
}

// About and Debug settings, registered with the host as Nav3 entries through a
// Hilt multibinding. The app binds the ports declared here (the background mesh
// switch and the transport diagnostics Debug reads).
dependencies {
    implementation(project(":feature:settings:api"))
    implementation(project(":feature:share:api"))
    implementation(project(":core:designsystem:phone"))
    implementation(project(":core:mesh"))
    implementation(project(":core:nostr"))
    implementation(project(":core:tor"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(testFixtures(project(":core:mesh")))
    testImplementation(libs.bundles.testing)
    // The Compose BOM for androidTest comes from bitchat.android.library.compose.
    androidTestImplementation(libs.bundles.compose.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Compose's ui-test drags in espresso-core 3.5.0, which dies on API 35+;
    // see the same pin in app/build.gradle.kts.
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Declares the empty ComponentActivity that createComposeRule launches.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
