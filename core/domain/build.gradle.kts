plugins {
    id("bitchat.android.library")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.bitchat.android.core.domain"
}

dependencies {
    implementation(libs.gson)
}
