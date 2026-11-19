package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module graph rules, read from the Gradle files: a feature reaches another feature only
 * through its `:api`, an `:api` module names only `:core:navigation` and `:core:domain`, and
 * `:core:tor` is a leaf, the shared `:core:designsystem` names no project and no Material3, and
 * `:core:designsystem:phone` builds on it alone, and `:core:readiness` names only `:core:mesh` and
 * `:core:domain`, so no feature and not `:app`.
 */
class FeatureDependencyRulesTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val projectRef = Regex("""project\(\s*"(:[^"]+)"\s*\)""")

    /** Project references in code only; comments are dropped. */
    private fun projectDependencies(buildFile: File): List<String> {
        val code = buildFile.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        return projectRef.findAll(code).map { it.groupValues[1] }.toList()
    }

    private fun featureBuildFiles(): List<File> =
        File(root, "feature").walkTopDown()
            .onEnter { it.name != "build" && it.name != "src" }
            .filter { it.name == "build.gradle.kts" }
            .toList()

    private fun modulePath(buildFile: File): String =
        ":" + buildFile.parentFile.relativeTo(root).path.replace(File.separatorChar, ':')

    @Test
    fun featureModulesAreDiscovered() {
        val modules = featureBuildFiles().map(::modulePath).toSet()
        assertTrue(
            "expected the five features and their apis, found $modules",
            modules.containsAll(
                listOf("share", "settings", "location", "chat", "onboarding")
                    .flatMap { listOf(":feature:$it", ":feature:$it:api") }
            )
        )
    }

    @Test
    fun featuresDependOnOtherFeaturesOnlyThroughApi() {
        val violations = featureBuildFiles().flatMap { file ->
            val self = modulePath(file)
            projectDependencies(file)
                .filter { it.startsWith(":feature:") && !it.endsWith(":api") }
                .map { "$self -> $it" }
        }
        assertTrue("features must use another feature's :api, not the feature: $violations", violations.isEmpty())
    }

    @Test
    fun apiModulesNameOnlyNavigationAndDomain() {
        val allowed = setOf(":core:navigation", ":core:domain")
        val violations = featureBuildFiles().filter { modulePath(it).endsWith(":api") }.flatMap { file ->
            projectDependencies(file).filter { it !in allowed }.map { "${modulePath(file)} -> $it" }
        }
        assertTrue("an :api module may depend only on $allowed: $violations", violations.isEmpty())
    }

    @Test
    fun torIsALeaf() {
        val deps = projectDependencies(File(root, "core/tor/build.gradle.kts"))
        assertTrue(":core:tor must depend on no project module: $deps", deps.isEmpty())
    }

    @Test
    fun readinessNamesOnlyMeshAndDomain() {
        val allowed = setOf(":core:mesh", ":core:domain")
        val deps = projectDependencies(File(root, "core/readiness/build.gradle.kts"))
        assertTrue(":core:readiness may depend only on $allowed: $deps", allowed.containsAll(deps))
    }

    @Test
    fun readinessDependsOnNoFeatureAndNotOnApp() {
        // The app's gate and the onboarding feature both build on it.
        val deps = projectDependencies(File(root, "core/readiness/build.gradle.kts"))
            .filter { it == ":app" || it.startsWith(":feature:") }
        assertTrue(":core:readiness must not depend on a feature or :app: $deps", deps.isEmpty())
    }

    private fun codeOf(buildFile: File): String =
        buildFile.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")

    @Test
    fun sharedDesignSystemDependsOnNoProjectAndNoMaterial() {
        val file = File(root, "core/designsystem/build.gradle.kts")
        val deps = projectDependencies(file)
        assertTrue(":core:designsystem must depend on no project module: $deps", deps.isEmpty())
        assertTrue(
            ":core:designsystem is shared with Wear and must not use Material3",
            !codeOf(file).contains("material3", ignoreCase = true)
        )
    }

    @Test
    fun phoneDesignSystemBuildsOnlyOnTheSharedOne() {
        val deps = projectDependencies(File(root, "core/designsystem/phone/build.gradle.kts"))
        assertTrue(":core:designsystem:phone may depend only on :core:designsystem: $deps", deps == listOf(":core:designsystem"))
    }
}
