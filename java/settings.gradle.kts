rootProject.name = "fraud-ring-linker"

// Modules move from Maven to Gradle one at a time (epic #17).
// The service module joins in #19; until then it builds with Maven only.
include("engine")

dependencyResolutionManagement {
    // Modules may not declare their own repositories: one place to audit where code comes from.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
