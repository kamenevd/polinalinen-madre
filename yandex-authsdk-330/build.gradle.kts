plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

group = "com.yandex.android"
version = "3.3.0"

// Exact published3.3.0 sources, with the narrowly documented custody correction.
// No publishing task/repository is configured; this is a local security source build.
android {
    namespace = "com.yandex.authsdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    buildFeatures {
        // Preserve the published SDK BuildConfig.java unchanged.
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Same dependency surface as the published3.3.0 POM; no Google services.
    api("androidx.activity:activity-ktx:1.9.3")
    api("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.browser:browser:1.5.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.2")
}
