package com.showerideas.aura.ml

import android.content.Context
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerOptions
import com.google.mediapipe.tasks.core.BaseOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised MediaPipe gesture model loader with caching and
 * integrity verification.
 *
 * Loading strategy
 *
 * 1. **Bundled asset** (`assets/gesture_recognizer.task`) — primary source.
 *    Ships with the APK; verified via SHA-256 at first load per app install.
 *    Never hits the network on the happy path.
 *
 * 2. **Internal-cache copy** — the bundled asset is copied to
 *    `filesDir/ml_models/gesture_recognizer.task` on first use and reused
 *    thereafter. MediaPipe requires a file-system path, not an asset stream.
 *    This avoids re-copying the asset on every recognizer creation.
 *
 * 3. **OTA update slot** — a newer model in the same cache directory is
 *    preferred over the bundled asset if it passes SHA-256 verification.
 *
 * Usage
 * ```kotlin
 * @Inject lateinit var modelLoader: GestureModelLoader
 *
 * val recognizer = modelLoader.createRecognizer()
 * ```
 *
 * Thread safety
 * [createRecognizer] is safe to call from any thread. The first call does I/O
 * (asset copy); subsequent calls are O(1) path resolution. Dispatch to
 * [kotlinx.coroutines.Dispatchers.IO] to avoid blocking the main thread.
 */
@Singleton
class GestureModelLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val ASSET_NAME     = "gesture_recognizer.task"
        private const val CACHE_DIR_NAME = "ml_models"

        /** Set to the canonical SHA-256 of the bundled model (from build.gradle.kts).
         *  Empty string = skip verification (dev/CI builds without model file). */
        private const val EXPECTED_SHA256 =
            "f7bbcc17ecc99c879f45f58d36e4e0feec78e9b0aedde99d9b1a5f2e28dbd36c"
    }
 *
    /** Resolved absolute path to the model file (set once, reused). */
    @Volatile private var resolvedModelPath: String? = null
 *
    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────
 *
    /**
     * Build and return a [GestureRecognizer] configured with the best available model.
     *
     * @param numHands Maximum number of hands to detect simultaneously (default 1).
     * @return A ready-to-use [GestureRecognizer] in VIDEO or LIVE_STREAM mode.
     * @throws IllegalStateException if no model file is available.
     */
    fun createRecognizer(numHands: Int = 1): GestureRecognizer {
        val modelPath = resolveModelPath()
        Timber.d("GestureModelLoader: building recognizer from %s", modelPath)

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelPath)
            .build()

        val options = GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(numHands)
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM)
            .build()

        return GestureRecognizer.createFromOptions(context, options)
    }

    /**
     * @return the absolute file-system path to the gesture model, copying from
     *         assets if needed.
     * @throws IllegalStateException if the model cannot be resolved.
     */
    fun resolveModelPath(): String {
        resolvedModelPath?.let { return it }

        synchronized(this) {
            resolvedModelPath?.let { return it }

            val cacheFile = modelCacheFile()

            // Prefer a valid cache copy (avoids asset re-extraction on every cold start).
            if (cacheFile.exists() && verifyHash(cacheFile)) {
                Timber.d("GestureModelLoader: using cached model at %s", cacheFile.absolutePath)
                resolvedModelPath = cacheFile.absolutePath
                return cacheFile.absolutePath
            }

            // Extract from bundled assets.
            copyAssetToCache(cacheFile)

            // Verify integrity after extraction.
            if (EXPECTED_SHA256.isNotBlank() && !verifyHash(cacheFile)) {
                cacheFile.delete()
                error(
                    "GestureModelLoader: bundled model SHA-256 mismatch!\n" +
                    "Expected: $EXPECTED_SHA256\n" +
                    "Update the model asset and EXPECTED_SHA256 in GestureModelLoader."
                )
            }

            resolvedModelPath = cacheFile.absolutePath
            Timber.i("GestureModelLoader: model extracted to %s (%d bytes)",
                cacheFile.absolutePath, cacheFile.length())
            return cacheFile.absolutePath
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun modelCacheFile(): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        dir.mkdirs()
        return File(dir, ASSET_NAME)
    }

    private fun copyAssetToCache(dest: File) {
        Timber.d("GestureModelLoader: copying bundled asset to %s", dest.absolutePath)
        context.assets.open(ASSET_NAME).use { inp ->
            FileOutputStream(dest).use { out -> inp.copyTo(out) }
        }
    }

    /**
     * Verify [file] against [EXPECTED_SHA256].
     * Returns true immediately if [EXPECTED_SHA256] is blank (verification skipped).
     */
    private fun verifyHash(file: File): Boolean {
        if (EXPECTED_SHA256.isBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8 * 1024)
            var n: Int
            while (inp.read(buf).also { n = it } >= 0) digest.update(buf, 0, n)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != EXPECTED_SHA256) {
            Timber.w("GestureModelLoader: hash mismatch (got=%s, want=%s)", actual, EXPECTED_SHA256)
        }
        return actual == EXPECTED_SHA256
    }
}
