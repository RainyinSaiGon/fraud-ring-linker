pluginManagement {
    // Shared conventions for the modules (java/build-logic).
    includeBuild("build-logic")
}

rootProject.name = "fraud-ring-linker"

include("engine", "service")

dependencyResolutionManagement {
    // Modules may not declare their own repositories: one place to audit where code comes from.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
