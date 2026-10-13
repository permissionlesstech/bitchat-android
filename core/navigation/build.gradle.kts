plugins {
    id("bitchat.android.library.compose")
    id("bitchat.android.hilt")
}

android {
    namespace = "com.bitchat.android.core.navigation"
}

dependencies {
    api(libs.androidx.navigation3.runtime)
    api(libs.androidx.navigation3.ui)
    api(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
