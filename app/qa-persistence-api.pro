# Actual minified QA used by same-certificate instrumentation: preserve the public
# persistence ABI (including a separate synthetic namespace), not Gson TypeToken.
# Main implementations still run under R8; anonymous TypeToken subclasses are NOT kept.
-keep class com.polinalinen.madre.navigation.BookPreferences { public *; }
# AndroidJUnitRunner calls this shared app dependency before starting any test.
# Keep its public ABI rather than pretending a runner startup crash was a data proof.
-keep class androidx.tracing.Trace { public *; }
