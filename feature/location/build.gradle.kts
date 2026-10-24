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

    testImplementation(libs.bundles.testing)
}
