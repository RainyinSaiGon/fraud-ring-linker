plugins {
    `java-library`
    id("fraudlinker.java-conventions")
    alias(libs.plugins.pitest)
}

description = "Constraint-aware, rarity-weighted linking of accounts controlled by the same fraud actor."

// Same artifact name as Maven: fraud-ring-linker-engine-<version>.jar
base {
    archivesName = "fraud-ring-linker-engine"
}

dependencies {
    api(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Mutation testing: `./gradlew :engine:pitest`.
// PIT plants small bugs (flips a condition, removes a call) and checks that some
// test fails. It is slow, so it is not part of `build`; CI runs it as its own job,
// only on the packages where a silent bug would change a fraud decision.
pitest {
    pitestVersion = libs.versions.pit.engine.get()
    junit5PluginVersion = libs.versions.pit.junit5.get()
    targetClasses = setOf(
        "io.github.rainyinsaigon.fraudlinker.scoring.*",
        "io.github.rainyinsaigon.fraudlinker.clustering.*",
    )
    targetTests = setOf("io.github.rainyinsaigon.fraudlinker.*")
    mutationThreshold = providers.gradleProperty("mutation.minimum").map { it.toInt() }
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
}

// Runnable CLI jar with Jackson bundled, at build/libs/fraud-ring-linker.jar.
// Replaces the Maven shade plugin; the plain module jar stays the library
// artifact the service depends on.
val cliJar = tasks.register<Jar>("cliJar") {
    group = "build"
    description = "Builds the runnable command-line jar with its dependencies."
    archiveFileName = "fraud-ring-linker.jar"
    manifest {
        attributes("Main-Class" to "io.github.rainyinsaigon.fraudlinker.cli.LinkerCli")
    }
    from(sourceSets.main.map { it.output })
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ runtime.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        // Signatures and module descriptors of the bundled jars would be invalid in a merged jar.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class",
            "META-INF/versions/*/module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.assemble {
    dependsOn(cliJar)
}
