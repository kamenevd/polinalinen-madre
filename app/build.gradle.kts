plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.polinalinen.madre"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.polinalinen.madre"
        minSdk = 26
        targetSdk = 35
        // v4.0.0 ground-up rewrite — versionCode/versionName перепроверить с Гесом
        // перед первым реальным коммитом в репозиторий (см. CLAUDE.md hard rule).
        versionCode = 7
        versionName = "4.6.0-cycle6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Cycle 5: PocketBase на домашнем сервере (LXC). Адрес меняется только
        // здесь — код берёт его исключительно из BuildConfig.MADRE_API_URL.
        buildConfigField("String", "MADRE_API_URL", "\"http://192.168.3.59:8091\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingConfigs.getByName("release").storeFile?.exists() == true) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation Compose — заменяет ручной Screen sealed class из v3
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Serialization (recipes.json)
    implementation("com.google.code.gson:gson:2.10.1")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // WorkManager — sourdough напоминания (закрывает баг v3 #2: CoroutineScope в BroadcastReceiver)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Retrofit — MadreApi поверх PocketBase (Cycle 5). Конвертер — Gson,
    // потому что Gson уже используется для recipes.json (не тянем второй JSON-стек).
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Coil — замена ручному BitmapFactory (закрывает баг v3 #4: OOM/jank на UI thread)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // ZXing core — QR гостевой страницы (Cycle 7, GuestPage). Только кодер
    // (чистая Java, без камеры/сканера) — рисуем BitMatrix сами на Canvas.
    implementation("com.google.zxing:core:3.5.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
