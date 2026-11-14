// Settings every Java module shares: compiler flags, tests, and coverage floors.
// Apply with `id("fraudlinker.java-conventions")`.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.math.BigDecimal

plugins {
    java
    jacoco
}

group = "io.github.rainyinsaigon"
version = "0.2.0-SNAPSHOT"

tasks.withType<JavaCompile>().configureEach {
    // Compile against the Java 21 API whatever JDK runs Gradle, exactly like Maven's
    // maven.compiler.release=21. A toolchain would force a JDK 21 download on machines
    // that only have a newer JDK; CI already runs the tests on JDK 21.
    options.release = 21
    options.encoding = "UTF-8"
    // Every lint warning fails the build.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

jacoco {
    toolVersion = "0.8.16"
}

// Coverage floors, set just under measured values. Raise them as coverage grows; never lower them.
// Defaults live in java/gradle.properties; a module overrides them in its own gradle.properties.
fun floor(name: String): BigDecimal =
    BigDecimal(findProperty(name)?.toString() ?: error("Gradle property '$name' is not set"))

val lineFloor = floor("coverage.line.minimum")
val branchFloor = floor("coverage.branch.minimum")

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = lineFloor
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = branchFloor
            }
        }
    }
}

// `check` (and so `build`) fails when coverage drops below a floor, like `mvn verify`.
tasks.named("check") {
    dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
}
