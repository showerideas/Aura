# Aura ProGuard rules

# Keep model classes (used with Gson / Room)
-keep class com.showerideas.aura.model.** { *; }

# Nearby Connections
-keep class com.google.android.gms.nearby.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Security crypto
-keep class androidx.security.crypto.** { *; }
