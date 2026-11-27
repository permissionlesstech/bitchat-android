plugins {
    id("bitchat.android.library.compose")
}

android {
    namespace = "com.bitchat.android.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.ui)

    testImplementation(libs.junit)
}
