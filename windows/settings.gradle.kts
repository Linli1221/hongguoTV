pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins { kotlin("jvm") version "2.1.20" }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "hongguotv-windows"
include(":core")
project(":core").projectDir = file("../kotlin-tv/core")
