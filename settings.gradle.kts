// Sill for Android: the client half of Sill (docs/BRIEF.md) for Android phones, tablets and
// foldables. It speaks the same wire protocol as iOSClient/ (Sources/StreamProtocol), so Sill for
// Mac needs no change to serve it.
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
    }
}
rootProject.name = "SillAndroid"
include(":app")
