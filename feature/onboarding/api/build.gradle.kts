plugins {
    id("bitchat.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bitchat.android.feature.onboarding.api"
}

// What the rest of the app may know about onboarding: its route, what it tells
// the host (OnboardingHost), and the scaffold padding the host hands it.
dependencies {
    api(project(":core:navigation"))
    api(libs.kotlinx.coroutines.android)
    // Aligns the Compose runtime that Navigation 3 brings (and its foundation
    // layout, for PaddingValues) with the rest of the app.
    implementation(platform(libs.androidx.compose.bom))
}
