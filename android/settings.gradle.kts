// android/settings.gradle.kts
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
        // RevenueCat SDK'sı Maven Central üzerinden dağıtılıyor — ayrı bir
        // repository eklemeye gerek yok.
    }
}

rootProject.name = "Eva"
include(":app")
