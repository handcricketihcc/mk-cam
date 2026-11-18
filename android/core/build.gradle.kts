plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mkcam.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2024.10.01"))
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.security:security-crypto:1.1.0-alpha06")
    api("io.github.webrtc-sdk:android:125.6422.07")
    api("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }
}
