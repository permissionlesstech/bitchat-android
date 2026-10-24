plugins {
    id("bitchat.android.library.compose")
}

android {
    namespace = "com.bitchat.android.core.designsystem.phone"
}

dependencies {
    api(project(":core:designsystem"))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
