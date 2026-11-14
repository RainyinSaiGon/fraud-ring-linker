rootProject.name = "fraud-ring-linker"

// Modules moved from Maven to Gradle one at a time (epic #17).
include("engine", "service")

dependencyResolutionManagement {
    // Modules may not declare their own repositories: one place to audit where code comes from.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
