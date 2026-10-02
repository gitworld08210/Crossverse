// :relay is the Ktor WebSocket relay server (Kotlin/JVM). It routes
// end-to-end-encrypted envelopes between paired devices that are on different
// networks (e.g. phone on mobile data, far away) and never sees plaintext.
// It builds and tests on the JVM in the sandbox.
plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("com.linkup.relay.MainKt")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}

tasks.test {
    useJUnit()
}
