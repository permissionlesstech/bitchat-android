pluginManagement {
    includeBuild("build-logic")
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
    }
}

rootProject.name = "bitchat-android"
include(":app")
include(":wear")
include(":core:domain")
include(":core:navigation")
include(":core:designsystem")
include(":core:designsystem:phone")
include(":core:mesh")
include(":core:nostr")
include(":core:chat")
include(":core:tor")
include(":feature:share")
include(":feature:share:api")
