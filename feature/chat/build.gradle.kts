plugins {
    id("bitchat.android.library.compose")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.feature.chat"
}

// The chat screens: chat, the peer list, private chats, verification and the
// chat user sheet, with their ViewModels and media, registered with the host as
// Nav3 entries through a Hilt multibinding.
dependencies {
    implementation(project(":feature:chat:api"))
    implementation(project(":feature:location:api"))
    implementation(project(":feature:settings:api"))
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

    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    // EXIF orientation handling for images
    implementation(libs.androidx.exifinterface)

    testImplementation(testFixtures(project(":core:mesh")))
    testImplementation(libs.bundles.testing)
    // MessagesListFollowTest drives the timeline under Robolectric.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The Compose BOM for androidTest comes from bitchat.android.library.compose.
    androidTestImplementation(libs.bundles.compose.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Compose's ui-test drags in espresso-core 3.5.0, which dies on API 35+;
    // see the same pin in app/build.gradle.kts.
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Declares the empty ComponentActivity that createComposeRule launches.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
