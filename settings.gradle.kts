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

rootProject.name = "StreamVault"

include(":app")
include(":benchmark")
include(":core:navigation")
include(":core:ui")
include(":domain")
include(":data")
include(":player")
include(":feature:playback")
