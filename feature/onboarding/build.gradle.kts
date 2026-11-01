plugins {
    id("bitchat.android.library.compose")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.feature.onboarding"
}

// The onboarding screens and their view model, registered with the host as a
// Nav3 entry through a Hilt multibinding. The device comes from :core:readiness;
// the app binds OnboardingHost.
dependencies {
    implementation(project(":feature:onboarding:api"))
    implementation(project(":core:readiness"))
    implementation(project(":core:designsystem:phone"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(testFixtures(project(":core:mesh")))
    testImplementation(libs.bundles.testing)
}
