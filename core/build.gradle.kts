plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Match the Android module (Java 17 bytecode) so the two modules resolve together under any JDK 17 or newer.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// The core module has no third-party dependencies on purpose: it is plain Kotlin/JVM, so every piece of survey logic
// (NMEA, NTRIP, projections, calibration, stakeout, import/export) can be tested on a laptop without an emulator.

// Dependency-free test runner (src/test/kotlin/.../Checks.kt). Run with:  ./gradlew :core:runChecks
tasks.register<JavaExec>("runChecks") {
    group = "verification"
    description = "Runs the core module's checks"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.openfield.core.ChecksKt")
    dependsOn("testClasses")
}

tasks.named("check") { dependsOn("runChecks") }
