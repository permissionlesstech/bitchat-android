import com.android.build.api.dsl.LibraryExtension
import com.bitchat.convention.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Compose configuration for a bitchat Android library module.
 *
 * includeComposeMappingFile is disabled to match :app and :wear. Kotlin
 * 2.4.10's optional Compose group-key mapping depends on unspecified
 * class-file iteration order, and leaving it on in a library would
 * reintroduce the nondeterminism the release pipeline byte-compares against.
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("bitchat.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            buildFeatures {
                compose = true
            }
        }

        extensions.configure<ComposeCompilerGradlePluginExtension> {
            includeComposeMappingFile.set(false)
        }

        dependencies {
            val bom = libs.findLibrary("androidx-compose-bom").get()
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
        }
    }
}
