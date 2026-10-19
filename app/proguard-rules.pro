# R8 rules for the Jarvis release build (isMinifyEnabled = true in app/build.gradle.kts).
#
# What is safe without rules: the app reads JSON with org.json by hand (no Gson, Moshi or
# kotlinx.serialization), uses no Class.forName or other reflection on its own classes and has no
# WebView @JavascriptInterface. Everything named in AndroidManifest.xml (MainActivity, FileProvider)
# is kept by AGP. Media3, Coil, coroutines, Compose and AndroidX ship their own consumer rules.

# Keep class and member names (shrinking and optimizing still run). Stack traces in logcat and the QA
# crash report stay readable, error text built from exception class names stays the same, and the
# baseline profile (src/main/baseline-prof.txt) matches the classes by name.
-dontobfuscate

# Readable stack traces.
-keepattributes SourceFile,LineNumberTable

# Kotlin enums are looked up by name (League, saved screen state); keep valueOf / values.
-keepclassmembers enum com.mcd.tv.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# BuildConfig fields (TMDB_KEY, MDBLIST_KEY) are read directly, so R8 may inline them. Keeping the class
# costs nothing and makes the baked-in values easy to check in the APK.
-keep class com.mcd.tv.BuildConfig { *; }

# Media3 picks optional decoder extensions (FFmpeg, AV1, Opus, FLAC) by class name when they are on the
# classpath. They are not bundled here; Media3's own rules cover the lookup. Silence notes about them.
-dontwarn androidx.media3.decoder.**
-dontnote androidx.media3.**

# ZXing core references javax / AWT helpers that are not on Android. Only QR encoding is used.
-dontwarn com.google.zxing.client.j2se.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Optional compile-time annotations some libraries reference.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.checkerframework.**
-dontwarn kotlin.annotations.jvm.**

# OkHttp (under Coil) has optional TLS providers that are not bundled.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Java 9+ string concatenation in bytecode; D8/R8 desugar it, but some libraries still reference it.
-dontwarn java.lang.invoke.StringConcatFactory

# Spotify App Remote: its AAR ships keep rules for its protocol types. Gson reads them by reflection, so keep the
# generic signatures Gson needs. Jackson is an optional mapper App Remote does not use here.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class com.spotify.protocol.types.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.spotify.base.annotations.**

# Release builds drop debug and verbose logs. Spotify's sign-in library logs the one-time sign-in code at debug
# level; with this rule that line never reaches logcat on a TV.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
