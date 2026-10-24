# Instrumentation APK only: Error Prone's compile-time annotation refers to javac's
# Modifier enum, which is not an Android runtime API. No app/R8/domain rule is weakened.
-dontwarn javax.lang.model.element.Modifier
# Keep only our public Android SDK instrumentation driver. There is no JUnit runner
# or test-framework startup dependency on optimized app Kotlin/tracing libraries.
-keep class com.polinalinen.madre.MinifiedPlanInstrumentation { *; }
-keep class com.polinalinen.madre.MinifiedPlanRegressionTest { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
