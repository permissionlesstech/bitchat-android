import com.android.build.api.dsl.LibraryExtension
import com.bitchat.convention.int
import com.bitchat.convention.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Baseline configuration for a bitchat Android library module.
 *
 * AGP 9 applies the Kotlin Android plugin itself, so this deliberately does
 * not apply org.jetbrains.kotlin.android — applying it would apply it twice.
 * That is also why gradle/libs.versions.toml has no kotlin-android entry.
 *
 * The SDK and Java levels mirror app/build.gradle.kts rather than introducing
 * new values. They must agree: :app compiles to Java 11 bytecode, and a
 * library emitting a higher class-file version would fail to dex.
 *
 * Configuration is written against LibraryExtension rather than the shared
 * CommonExtension because AGP 9 declares the block-syntax overloads
 * (defaultConfig, compileOptions, lint) only on the concrete extension types;
 * CommonExtension exposes getters alone.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = libs.int("compileSdk")
            defaultConfig {
                minSdk = libs.int("minSdk")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            lint {
                abortOnError = false
                checkReleaseBuilds = false
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }
}
