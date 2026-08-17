import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

val githubReleaseCertSha256 = providers
    .environmentVariable("BITCHAT_GITHUB_RELEASE_CERT_SHA256")
    .orElse(providers.gradleProperty("BITCHAT_GITHUB_RELEASE_CERT_SHA256"))
    .orElse("")
val normalizedGithubReleaseCertSha256 = githubReleaseCertSha256.get()
    .replace(":", "")
    .trim()
    .lowercase()
require(
    normalizedGithubReleaseCertSha256.isEmpty() ||
        normalizedGithubReleaseCertSha256.matches(Regex("[a-f0-9]{64}"))
) {
    "BITCHAT_GITHUB_RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint"
}

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "com.bitchat.droid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 38
        versionName = "2.0.1"
        buildConfigField(
            "String",
            "GITHUB_RELEASE_CERT_SHA256",
            "\"$normalizedGithubReleaseCertSha256\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        debug {
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo {
                // BUILDINFO.json and attestations carry the verified commit
                // without depending on host-specific Git/worktree paths.
                include = false
            }
        }
    }

    // APK splits for GitHub releases - creates arm64, x86_64, and universal APKs
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

composeCompiler {
    // Kotlin 2.4.10's optional Compose group-key mapping depends on unspecified
    // class-file iteration order. Keep the normal R8 mapping, but omit that
    // augmentation until its producer is deterministic across clean builds.
    includeComposeMappingFile.set(false)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // WorkManager for background APK downloads
    implementation(libs.androidx.work.runtime.ktx)

    // HTTP Server for hotspot APK sharing
    implementation(libs.nanohttpd)

    // Arti (Tor in Rust) Android bridge - custom build from latest source
    // Built with rustls, 16KB page size support, and onio//un service client
    // Native libraries are in src/tor/jniLibs/ (extracted from arti-custom.aar)
    // Only included in tor flavor to reduce APK size for standard builds
    // Note: AAR is kept in libs/ for reference, but libraries loaded from jniLibs/

    // Google Play Services Location
    implementation(libs.gms.location)

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // EXIF orientation handling for images
    implementation(libs.androidx.exifinterface)
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Robolectric resolves Android runtime jars itself (outside Gradle dependency resolution).
// Its legacy repo1 endpoint rejects cold GitHub-hosted runners with HTTP 403.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://repo.maven.apache.org/maven2"
    )
}
