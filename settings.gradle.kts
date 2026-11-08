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
include(":app", ":core")

// Future independently testable modules from docs/ARCHITECTURE.md can be added
// here as they are implemented: :core, :providers, :mcp, :permissions,
// :storage, and :workspace.
