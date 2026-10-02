// Root build file for the Linkup multi-module project.
// Plugins are declared here with `apply false` so that each subproject can
// apply them with a consistent, catalog-pinned version. The :core and :relay
// modules are pure Kotlin/JVM and build & test in the sandbox without the
// Android SDK; the :app module is an Android application the user builds in
// Android Studio.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
