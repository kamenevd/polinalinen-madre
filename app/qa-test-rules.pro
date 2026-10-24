# Instrumentation APK only: Error Prone's compile-time annotation refers to javac's
# Modifier enum, which is not an Android runtime API. No app/R8/domain rule is weakened.
-dontwarn javax.lang.model.element.Modifier
