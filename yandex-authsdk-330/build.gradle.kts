plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

group = "com.yandex.android"
version = "3.3.0"

val yandexClientId = providers.environmentVariable("MADRE_YANDEX_CLIENT_ID").orElse("").get()
require(yandexClientId.isEmpty() || yandexClientId.matches(Regex("[0-9a-fA-F]{32}"))) {
    "Invalid public Yandex client id"
}

// Exact published3.3.0 sources, with the narrowly documented custody correction.
// No publishing task/repository is configured; this is a local security source build.
android {
    namespace = "com.yandex.authsdk"
    // Keep upstream CompatUtils source and certificate handling unchanged.
    // API35 adds nullable platform annotations absent from this pinned source;
    // API34 supports its API33 calls and AndroidX dependencies. The app's own
    // compile/target SDK and the runtime35 custody regression are unchanged.
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        // Library androidTest merges its own main manifest independently of the consuming app.
        // The same approved CI input is inherited by both library variants and their test APKs.
        manifestPlaceholders["YANDEX_CLIENT_ID"] = yandexClientId.ifEmpty { "disabled-madre-id" }
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
