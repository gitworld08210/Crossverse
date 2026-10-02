// :core is a pure Kotlin/JVM library. It holds all platform-independent logic
// (wire protocol, pairing, end-to-end crypto helpers, domain models) and has
// NO Android dependencies, so it compiles and unit-tests on the JVM in CI /
// the sandbox without the Android SDK.
plugins {
    kotlin("jvm")
    `java-library`
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
