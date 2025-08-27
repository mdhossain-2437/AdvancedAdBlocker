# ProGuard/R8 rules for production

# Keep all app classes (shrink can be tuned later per package)
-keep class com.example.adblocker.** { *; }

# Keep JNI bridge classes and methods (NativeProxy)
-keep class com.example.adblocker.native.** { *; }

# Keep WorkManager (runtime uses reflection)
-keep class androidx.work.** { *; }
-keep class androidx.startup.** { *; }

# Keep Gson model classes and annotations
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp/Okio are generally safe; suppress warnings if any arise from shaded code
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Kotlin metadata for reflection (optional)
-keepclassmembers class kotlin.Metadata { *; }

# Do not obfuscate enum names (safer logging and reflection)
-keepclassmembers enum * { *; }
