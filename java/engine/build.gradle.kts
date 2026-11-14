plugins {
    `java-library`
}

group = "io.github.rainyinsaigon"
version = "0.2.0-SNAPSHOT"
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

tasks.withType<JavaCompile>().configureEach {
    // Compile against the Java 21 API whatever JDK runs Gradle, exactly like Maven's
    // maven.compiler.release=21. A toolchain would force a JDK 21 download on machines
    // that only have a newer JDK; CI already runs the tests on JDK 21.
    options.release = 21
    options.encoding = "UTF-8"
    // Same as the Maven build: every lint warning fails the build.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
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
