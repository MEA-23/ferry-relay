// Ferry crypto (docs/PROTOCOL.md §3) in pure Kotlin on BouncyCastle, so it runs in plain JVM
// unit tests against docs/test-vectors — the same vectors the TypeScript reference generates.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

val vectors = rootDir.resolve("../docs/test-vectors").canonicalFile
tasks.withType<Test>().configureEach {
    systemProperty("ferry.testVectors", vectors.path)
    inputs.dir(vectors).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("testVectors")
}
