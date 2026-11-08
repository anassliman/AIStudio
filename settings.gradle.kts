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

rootProject.name = "AIStudio"
include(":app", ":core", ":storage")

// Future independently testable modules from docs/ARCHITECTURE.md can be added
// as they are implemented: :providers, :mcp, :permissions, and :workspace.
