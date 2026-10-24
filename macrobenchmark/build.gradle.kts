plugins { id("com.android.test") }
android {
    namespace="com.polinalinen.madre.macrobenchmark"
    compileSdk=35
    defaultConfig {
        minSdk=28
        targetSdk=35
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
        // Explicitly authorized remote CI AVD; this never claims physical Huawei performance.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"]="EMULATOR"
    }
    targetProjectPath=":app"
    experimentalProperties["android.experimental.self-instrumenting"]=true
    buildTypes.create("benchmark") {
        // The self-instrumenting TEST APK needs a certificate too; target app keeps stable QA signing.
        initWith(buildTypes.getByName("debug"))
        matchingFallbacks.add("release")
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
