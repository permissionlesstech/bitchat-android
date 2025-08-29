pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Guardian Project maven repo for Tor Android binaries
        maven {
            url = uri("https://guardianproject.github.io/gpmaven/")
            content { includeGroup("org.torproject") }
        }
        // Briar project mirror sometimes hosts tor-android-binary
        maven {
            url = uri("https://maven.briarproject.org")
            content { includeGroup("org.torproject") }
        }
        // Guardian Project raw GitHub Maven repo (authoritative)
        maven {
            url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master")
            content { includeGroup("org.torproject") }
        }
    }
}

rootProject.name = "bitchat-android"
include(":app")
