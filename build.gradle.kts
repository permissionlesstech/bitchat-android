import org.gradle.api.artifacts.dsl.LockMode

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

val resolveIdeRuntimeClasspathCopyLocks = tasks.register("resolveIdeRuntimeClasspathCopyLocks") {
    group = "build setup"
    description = "Resolves Android Studio runtime classpath copies when refreshing lock state."
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }

    // Robolectric resolves Android runtime jars itself (outside Gradle dependency resolution).
    // Its legacy repo1 endpoint rejects cold GitHub-hosted runners with HTTP 403. Set for every
    // module, because Robolectric tests live in :core:mesh as well as :app.
    tasks.withType<Test>().configureEach {
        systemProperty(
            "robolectric.dependency.repo.url",
            "https://repo.maven.apache.org/maven2"
        )
    }

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
            finalizeDsl { android ->
                // Enforce the effective module target, including overrides of the shared catalog.
                // API 36 meets the 2026 phone requirement and also covers Wear OS.
                check((android.defaultConfig.targetSdk ?: 0) >= 36) {
                    "$path must target API 36 or higher for Google Play updates. " +
                        "Update targetSdk in gradle/libs.versions.toml and remove lower module overrides."
                }
                check(android.productFlavors.toList().all { (it.targetSdk ?: android.defaultConfig.targetSdk ?: 0) >= 36 }) {
                    "$path product flavors must target API 36 or higher for Google Play updates."
                }
            }
        }

        val resolveIdeRuntimeClasspathCopyLock = tasks.register("resolveIdeRuntimeClasspathCopyLock") {
            group = "build setup"
            description = "Resolves this module's Android Studio runtime classpath copies."
            notCompatibleWithConfigurationCache("Resolves copied configurations at execution time")
            doFirst {
                check(gradle.startParameter.isWriteDependencyLocks) {
                    "$path must be run with --write-locks"
                }
            }
            doLast {
                listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { configurationName ->
                    configurations.getByName(configurationName).copy().resolve()
                }
            }
        }
        resolveIdeRuntimeClasspathCopyLocks.configure {
            dependsOn(resolveIdeRuntimeClasspathCopyLock)
        }
    }
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

tasks.register("clientRewriteContractTest") {
    group = "verification"
    description = "Runs the complete compatibility gate for a from-scratch client rewrite."
    dependsOn(
        ":app:testDebugUnitTest",
        ":core:mesh:testDebugUnitTest",
        ":core:nostr:testDebugUnitTest",
        ":core:chat:testDebugUnitTest",
    )
}
