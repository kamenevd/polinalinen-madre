# Instrumentation APK only: Error Prone's compile-time annotation refers to javac's
# Modifier enum, which is not an Android runtime API. No app/R8/domain rule is weakened.
-dontwarn javax.lang.model.element.Modifier
# Runner/test discovery is reflective. These are TEST APK classes only; the actual
# qa target keeps its original full R8 optimization/obfuscation and packed ART assets.
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class com.polinalinen.madre.MinifiedPlanRegressionTest { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
