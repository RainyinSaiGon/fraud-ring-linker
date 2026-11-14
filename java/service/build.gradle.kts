plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "io.github.rainyinsaigon"
version = "0.2.0-SNAPSHOT"
description = "REST service around the linking engine: live assignment, cluster explanations, analyst decisions and an audit log."

dependencies {
    implementation(project(":engine"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    // In-memory database for the `h2` profile: run and test the service without Docker or PostgreSQL.
    runtimeOnly(libs.h2)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.rest.assured)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // Java 21 API, every lint warning fails the build: the same as the engine and the Maven build.
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Runnable Spring Boot jar with the same name as the Maven build:
// build/libs/fraud-ring-linker-service.jar
tasks.bootJar {
    archiveFileName = "fraud-ring-linker-service.jar"
}

// The plain (non-runnable) jar would sit next to it and only confuse people.
tasks.jar {
    enabled = false
}
