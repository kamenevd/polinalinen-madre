plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.polinalinen.madre"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.polinalinen.madre"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.6.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    signingConfigs {
        create("release") {
            storeFile = file("/root/.openclaw/workspace/levito-keystore/levito-madre.keystore")
            storePassword = "kdnfx_levito_2026"
            keyAlias = "levito"
            keyPassword = "kdnfx_levito_2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Serialization (for recipes.json)
    implementation("com.google.code.gson:gson:2.10.1")

    // Notifications
    implementation("androidx.core:core:1.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.2.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
