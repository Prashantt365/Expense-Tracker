# Referenced by app/build.gradle.kts; the release build fails outright if this file is missing.

# Room generates an implementation per @Database and reflects over entity constructors.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ML Kit text recognition loads its models through reflection.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-dontwarn com.google.mlkit.**

# Kotlin coroutines' internals are resolved reflectively by the debug agent.
-dontwarn kotlinx.coroutines.**

# Keep line numbers so release crash reports stay readable, without leaking source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
