// :app is the Android application module. It requires the Android SDK and is
// built by the user in Android Studio (API 26+). It depends on :core for the
// shared protocol / crypto / pairing logic and provides the Android-specific
// glue: NSD/mDNS LAN discovery, foreground sync service, clipboard access,
// screenshot capture, the relay WebSocket client, and the Compose UI.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.linkup.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linkup.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default relay host. The user replaces this with their deployed :relay
        // (e.g. "wss://relay.example.com/ws") so off-LAN / remote-phone sync
        // works. Overridable per build type below. See docs/VERIFICATION.md.
        buildConfigField(
            "String",
            "RELAY_URL",
            "\"wss://relay.linkup.example/ws\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Relay WebSocket client (used when peers are on different networks).
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    // Encrypted storage for pairing secrets / link key material.
    implementation(libs.androidx.security.crypto)

    // QR pairing: generate the QR bitmap (ZXing core) + scan via camera.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
}
