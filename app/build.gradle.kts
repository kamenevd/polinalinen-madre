plugins {
    // kotlin.android здесь не применяется намеренно: с AGP 9 поддержка Kotlin
    // встроена в сам AGP, и плагин поверх неё роняет конфигурацию.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.github.takahirom.roborazzi")
}

val keystorePath = System.getenv("KEYSTORE_PATH")
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val releaseSigningInputs = listOf(
    keystorePath,
    keystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasCompleteReleaseSigning = releaseSigningInputs.all { !it.isNullOrBlank() }
val hasAnyReleaseSigning = releaseSigningInputs.any { !it.isNullOrBlank() }
if (hasAnyReleaseSigning && !hasCompleteReleaseSigning) {
    throw GradleException("Release signing inputs are incomplete")
}

android {
    namespace = "com.polinalinen.madre"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.polinalinen.madre"
        minSdk = 26
        targetSdk = 35
        // versionCode is monotonic over the latest published release; release_cycle.py enforces it.
        versionCode = 36
        versionName = "6.6.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Cycle 11: PocketBase переехал из домашней сети в production и виден
        // только по HTTPS. Адрес меняется только здесь — код берёт его
        // исключительно из BuildConfig.MADRE_API_URL.
        buildConfigField("String", "MADRE_API_URL", "\"https://madre-api.kdnfx.space\"")
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(keystorePath))
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

    // jvmTarget с AGP 9 задаётся через kotlin { compilerOptions }, блока
    // android.kotlinOptions больше нет.

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2048m"
        }
    }

    // MigrationTestHelper ищет схемы среди ассетов инструментального APK —
    // без этого он не сможет собрать БД старой версии и молча провалит тест.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Схема Room выгружается в app/schemas и лежит в git: без неё
// MigrationTestHelper (androidTest/data/db/MigrationTest.kt) не может открыть
// БД старой версии и проверить миграции на настоящих данных.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val verifyReleaseSigning = tasks.register<Exec>("verifyReleaseSigning") {
    commandLine("python3", rootProject.file("scripts/check_release_signing.py"))
}

tasks.matching { it.name == "packageRelease" || it.name == "signReleaseBundle" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

configurations.all {
    // Cycle 16: Room 2.8.4 транзитивно фиксирует kotlinx-serialization-core
    // на 1.7.3 через {strictly}, а json подтягивается 1.8.1 — на устройстве
    // это AbstractMethodError в MigrationTestHelper. Выравниваем на 1.8.1.
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0")
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.11.0")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
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

    // Room Database. 2.8.x — первая линейка, чей процессор умеет KSP2;
    // 2.6.1 на Kotlin 2.4 просто не запускается.
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.70.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.70.0")
    // Cycle 12: Compose-взаимодействия проверяются на Robolectric, а не только
    // на устройстве. Эмулятора в этой сборочной среде нет, а правила «отмена
    // спрашивает» и «мишень не меньше 48dp» проверять глазами нельзя.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    // Cycle 15: миграции Room проверяются на настоящей SQLite, а не на глаз.
    // История схем лежит в app/schemas — MigrationTestHelper поднимает БД
    // нужной версии оттуда.
    // Cycle 16: Room 2.8.4 тянет kotlinx-serialization-core 1.7.3, а json — 1.8.1.
    // GeneratedSerializer.typeParametersSerializers() появился в 1.8.x, и
    // MigrationTestHelper на устройстве падал с AbstractMethodError.
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    androidTestImplementation("com.google.truth:truth:1.2.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}