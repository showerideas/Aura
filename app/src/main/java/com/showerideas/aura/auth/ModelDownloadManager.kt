package com.showerideas.aura.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gesture model manager for the FOSS (non-GMS) flavor.
 *
 * F-Droid policy prohibits downloading binaries at *build* time from non-audited URLs.
 * For the FOSS variant the model is therefore downloaded at *first app launch* and
 * cached in the app's private files directory. The SHA-256 is verified before the
 * file is accepted so a tampered model cannot be injected.
 *
 * The GMS flavor continues to use the `downloadGestureModel` Gradle task which
 * bundles the model in `assets/` at build time.
 *
 * Call site
 * ```kotlin
 * val modelPath = modelDownloadManager.requireModel()  // suspending, call from IO
 * BaseOptions.builder().setModelAssetPath(modelPath).build()
 * ```
 *
 * Files layout
 * ```
 * app internal storage:
 *   gesture_recognizer.task   — the verified model
 *   gesture_recognizer.task.sha256  — stored hash (avoids re-verify on restart)
 * ```
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val MODEL_FILENAME  = "gesture_recognizer.task"
        private const val MODEL_URL       =
            "https://storage.googleapis.com/mediapipe-models/" +
            "gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task"
        /**
         * SHA-256 of gesture_recognizer.task (float16/latest, verified 2026-05-24).
         * Update whenever the MediaPipe team publishes a new model version.
         */
        const val EXPECTED_SHA256 =
            "97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482"
    }

    private val modelFile get() = File(context.filesDir, MODEL_FILENAME)

    /**
     * Returns the absolute path to a verified gesture model file.
     *
     * - If the model is already present and SHA-256 matches, returns immediately.
     * - Otherwise downloads from [MODEL_URL], verifies SHA-256, then returns.
     *
     * Must be called from a coroutine on [Dispatchers.IO].
     *
     * @throws ModelDownloadException if the download or verification fails.
     */
    suspend fun requireModel(): String = withContext(Dispatchers.IO) {
        if (modelFile.exists() && verifySha256(modelFile)) {
            Timber.d("ModelDownloadManager: model already present and verified.")
            return@withContext modelFile.absolutePath
        }

        Timber.i("ModelDownloadManager: downloading gesture model from CDN...")
        downloadModel()

        if (!verifySha256(modelFile)) {
            modelFile.delete()
            throw ModelDownloadException(
                "Downloaded model failed SHA-256 verification. " +
                "Expected $EXPECTED_SHA256"
            )
        }

        Timber.i("ModelDownloadManager: model downloaded and verified ✓")
        modelFile.absolutePath
    }

    /** True if the model already exists and its SHA-256 matches [EXPECTED_SHA256]. */
    fun isModelReady(): Boolean = modelFile.exists() && verifySha256(modelFile)

    // Private

    private fun downloadModel() {
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        try {
            val conn = URL(MODEL_URL).openConnection().apply {
                connectTimeout = 15_000
                readTimeout    = 5 * 60 * 1_000
            }
            conn.getInputStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (!tempFile.renameTo(modelFile)) {
                tempFile.delete()
                throw ModelDownloadException("Failed to move downloaded model to ${modelFile.absolutePath}")
            }
        } catch (e: IOException) {
            tempFile.delete()
            throw ModelDownloadException("Model download failed: ${e.message}", e)
        }
    }

    private fun verifySha256(file: File): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(EXPECTED_SHA256, ignoreCase = true)
        } catch (e: Exception) {
            Timber.w(e, "SHA-256 verification error")
            false
        }
    }

    class ModelDownloadException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}

