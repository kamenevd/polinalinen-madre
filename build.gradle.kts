plugins {
    id("com.android.application") version "9.3.1" apply false
    // С AGP 9 Kotlin встроен в сам AGP, но тянет за собой KGP 2.2.10.
    // Объявление здесь (без apply) поднимает KGP на classpath сборки до 2.4.10 —
    // именно его и использует встроенная поддержка Kotlin.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    // С Kotlin 2.0 версия Compose-компилятора задаётся этим плагином,
    // а не composeOptions.kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    // KSP вместо kapt: Room-процессор работает без генерации Java-стабов.
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("io.github.takahirom.roborazzi") version "1.70.0" apply false
}
