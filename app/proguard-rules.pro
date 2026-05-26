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

# ---------------------------------------------------------------------------
# Prompt-5: MediaPipe Tasks Vision — GestureRecognizer
#
# MediaPipe loads its Task runner, inference engine, and result-parsing code
# via JNI + reflection. R8 will strip ALL of these classes without explicit
# -keep rules, causing a crash at the first gesture attempt on a release build.
#
# Rules cover:
#   com.google.mediapipe.tasks.vision.**      — GestureRecognizer, result types
#   com.google.mediapipe.tasks.core.**        — BaseOptions, TaskInfo, delegates
#   com.google.mediapipe.framework.**         — MPImage, ImageBuilder, JNI wrappers
#   com.google.mediapipe.tasks.components.**  — NormalizedLandmark, Category
#
# Verification command (run after assembleRelease):
#   $ANDROID_HOME/tools/bin/apkanalyzer classes list app-release-unsigned.apk \
#     | grep "mediapipe"
# Expected: com.google.mediapipe.tasks.vision.gesturerecognizer.* present.
# ---------------------------------------------------------------------------
-keep class com.google.mediapipe.tasks.vision.** { *; }
-keep class com.google.mediapipe.tasks.core.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.tasks.components.** { *; }
-keep class com.google.mediapipe.proto.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# CameraX — ImageProxy / ImageAnalysis called from background threads;
# some implementations use reflection to locate the image plane accessors.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# javax.lang.model + javax.annotation.processing (Java compiler API) — not
# present on Android. Referenced by AutoValue annotation-processor classes
# (MemoizedValidator, etc.) that leak into the runtime classpath via the
# com.google.auto.value:auto-value-annotations transitive dependency.
# R8 fails if it can't resolve the references; -dontwarn suppresses the
# errors since none of this annotation-processor code runs on device.
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.processing.**
-dontwarn autovalue.shaded.com.squareup.javapoet.**
-dontwarn com.google.auto.value.extension.memoized.processor.**
# ---------------------------------------------------------------------------
# T43: BouncyCastle PQ keep rules — CRITICAL for HybridKEM release builds
#
# HybridKEM.kt uses ML-KEM-768 (FIPS 203) + X25519 via org.bouncycastle.pqc.
# R8 strips all BouncyCastle PQ classes without these rules, producing a
# NoClassDefFoundError at the first PQ key generation on release builds.
#
# Rules cover:
#   org.bouncycastle.pqc.**       — ML-KEM-768, Kyber, NTRU, SPHINCS+ families
#   org.bouncycastle.crypto.**    — AES-GCM, HKDF, ECDH, X25519 providers
#   org.bouncycastle.math.**      — ECC arithmetic (X25519 field operations)
#   org.bouncycastle.asn1.**      — ASN.1 serialization for key encoding
#   org.bouncycastle.jcajce.**    — JCA/JCE bridge provider
#   org.bouncycastle.jce.**       — JCE provider registration
#   org.bouncycastle.x509.**      — Certificate utilities
#
# Verification (run after assembleRelease):
#   $ANDROID_HOME/tools/bin/apkanalyzer classes list app-release-unsigned.apk \
#     | grep "bouncycastle.pqc"
# Expected: org.bouncycastle.pqc.crypto.mlkem.* present.
# ---------------------------------------------------------------------------
-keep class org.bouncycastle.pqc.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.x509.** { *; }
-keep class org.bouncycastle.util.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Timber — log wrapper; reflection-safe but dontwarn keeps R8 quiet.
-dontwarn timber.log.**
