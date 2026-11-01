plugins {
    id("bitchat.android.library")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.core.readiness"
}

// What the device and the stored onboarding choices say about readiness: Bluetooth,
// location services, battery optimization, runtime permissions, first launch and the
// skip preferences, read in one place. Wi-Fi Aware support comes from the app through
// WifiAwareAvailability. Depends on :core:mesh and :core:domain only.
dependencies {
    implementation(project(":core:mesh"))
    implementation(project(":core:domain"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(testFixtures(project(":core:mesh")))
    testImplementation(libs.bundles.testing)
}
