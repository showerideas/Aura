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

# PR-08: ZXing / journeyapps QR scanner — reflection-heavy.
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**

# PR-07: FileProvider for vCard share intents.
-keep class androidx.core.content.FileProvider { *; }

# PR-14: BlockedEndpoint model — explicitly kept (already covered by the
# model.** rule above, but pinned here so future refactors of the umbrella
# rule don't accidentally drop it).
-keep class com.showerideas.aura.model.BlockedEndpoint { *; }

# FIX-2: KnownPeer — persisted TOFU registry entity. Pinned for the same
# reason as BlockedEndpoint; Room reflectively accesses field names.
-keep class com.showerideas.aura.model.KnownPeer { *; }
