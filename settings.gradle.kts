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

rootProject.name = "NekoStatusMobile"

include(
    ":app",
    ":core:model",
    ":core:network",
    ":core:data",
    ":core:database",
    ":core:designsystem",
    ":core:testing",
    ":feature:auth",
    ":feature:overview",
    ":feature:activity",
    ":feature:devices",
    ":feature:settings",
)
