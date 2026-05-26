package com.showerideas.aura.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private val Context.classifierDataStore by preferencesDataStore("aura_gesture_classifier")

/**
 * Phase 9.4 — On-device gesture embedding classifier.
 *
 * Takes the 63-float hand-landmark embeddings produced by [CameraHandEmbedder]
 * and makes a binary authentication decision: "is this the user's enrolled
 * gesture?" (ACCEPT) or "is this a different/spoofed gesture?" (REJECT).
 *
 * ## Algorithm
 * During enrolment [train] is called with N captured embeddings (N ≥ 3
 * recommended). The classifier computes:
 *   1. A centroid (per-dimension mean) over the enrolled samples.
 *   2. A spread radius: the 95th-percentile cosine distance from the centroid.
 *
 * During authentication [predict] computes the cosine similarity of the
 * candidate embedding to the centroid and returns a normalised confidence
 * score in [0, 1]. A score ≥ [CONFIDENCE_GATE] results in ACCEPT.
 *
 * This is a lightweight "meta-embedding" approach that works well for
 * single-class personalised biometrics without requiring a GPU or JNI TFLite
 * invocation. A full on-device TFLite variant (FlatBuffer transfer-learning
 * model, updating only the final classification layer) is the planned Phase
 * 9.4-b upgrade.
 *
 * ## Persistence
 * The centroid and spread are serialised as a float array and stored in
 * DataStore (as a base64 FlatBuffer blob), surviving app restarts without
 * re-enrolment.
 *
 * @see CameraHandEmbedder.EMBEDDING_SIZE
 */
@Singleton
class GestureClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Minimum confidence to accept a gesture as authenticated. Range: [0, 1]. */
        const val CONFIDENCE_GATE = 0.82f

        /** Minimum number of enrolled samples required before classification is valid. */
        const val MIN_ENROLL_SAMPLES = 3

        /** Embedding dimension — must match [CameraHandEmbedder.EMBEDDING_SIZE]. */
        private const val EMBED_DIM = 63

        private val KEY_CENTROID = stringPreferencesKey("gesture_classifier_centroid_b64")
        private val KEY_SPREAD   = stringPreferencesKey("gesture_classifier_spread_b64")
    }

    /** Classification result. */
    data class Prediction(
        val confidence: Float,
        val accepted  : Boolean
    )

    // In-memory state (loaded lazily from DataStore)
    @Volatile private var centroid : FloatArray? = null
    @Volatile private var spread   : Float       = 1f

    // -------------------------------------------------------------------------
    // Enrolment API
    // -------------------------------------------------------------------------

    /**
     * Train the classifier on [enrolledEmbeddings].
     *
     * @param enrolledEmbeddings At least [MIN_ENROLL_SAMPLES] embeddings from the
     *                           user's enrolled gesture, each of length [EMBED_DIM].
     * @throws IllegalArgumentException if fewer than [MIN_ENROLL_SAMPLES] valid
     *                                  embeddings are provided.
     */
    suspend fun train(enrolledEmbeddings: List<FloatArray>) = withContext(Dispatchers.Default) {
        val valid = enrolledEmbeddings.filter { it.size == EMBED_DIM && !it.all { v -> v == 0f } }
        require(valid.size >= MIN_ENROLL_SAMPLES) {
            "Classifier requires at least $MIN_ENROLL_SAMPLES valid embeddings, got ${valid.size}"
        }

        // Compute centroid
        val c = FloatArray(EMBED_DIM)
        for (emb in valid) {
            for (i in emb.indices) c[i] += emb[i]
        }
        for (i in c.indices) c[i] /= valid.size

        // Normalise centroid to unit sphere
        val cNorm = l2Norm(c)
        if (cNorm > 0f) for (i in c.indices) c[i] /= cNorm

        // Compute spread: mean cosine distance of enrolled samples from centroid
        val distances = valid.map { 1f - cosineSim(it, c) }
        val s = (distances.sorted().let { sorted ->
            val p95idx = (sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)
            sorted[p95idx]
        }).coerceAtLeast(0.01f)  // minimum spread guard

        Timber.i("GestureClassifier: trained on ${valid.size} samples, spread=$s")

        centroid = c
        spread   = s
        persist(c, s)
    }

    // -------------------------------------------------------------------------
    // Authentication API
    // -------------------------------------------------------------------------

    /**
     * Predict whether [embedding] matches the enrolled gesture.
     *
     * @return [Prediction] with [Prediction.confidence] in [0, 1] and
     *         [Prediction.accepted] = true if confidence ≥ [CONFIDENCE_GATE].
     * @return confidence=0, accepted=false if no model has been trained yet.
     */
    suspend fun predict(embedding: FloatArray): Prediction {
        val c = centroid ?: loadCentroid() ?: run {
            Timber.w("GestureClassifier: predict called before training")
            return Prediction(0f, false)
        }

        if (embedding.size != EMBED_DIM) {
            Timber.w("GestureClassifier: wrong embedding size ${embedding.size}")
            return Prediction(0f, false)
        }

        val sim      = cosineSim(embedding, c)               // [−1, 1]
        val distance = 1f - sim                              // [0, 2]
        // Normalise by spread: distance=0 → confidence=1, distance=spread → confidence≈0.5
        val rawConf  = 1f / (1f + (distance / spread))      // sigmoid-like decay
        val conf     = rawConf.coerceIn(0f, 1f)

        val accepted = conf >= CONFIDENCE_GATE
        Timber.d("GestureClassifier: sim=$sim dist=$distance conf=$conf accepted=$accepted")
        return Prediction(conf, accepted)
    }

    /** True if a trained model is available (either in memory or DataStore). */
    suspend fun isModelTrained(): Boolean = centroid != null || loadCentroid() != null

    /** Clear the trained model from memory and DataStore (e.g. on account reset). */
    suspend fun clear() {
        centroid = null
        spread   = 1f
        context.classifierDataStore.edit { it.clear() }
    }

    // -------------------------------------------------------------------------
    // Persistence (FlatBuffer-style: centroid + spread as raw float bytes)
    // -------------------------------------------------------------------------

    private suspend fun persist(c: FloatArray, s: Float) = withContext(Dispatchers.IO) {
        val buf = ByteBuffer.allocate((EMBED_DIM + 1) * 4).order(ByteOrder.LITTLE_ENDIAN)
        c.forEach { buf.putFloat(it) }
        buf.putFloat(s)
        val b64 = android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP)
        context.classifierDataStore.edit { prefs ->
            prefs[KEY_CENTROID] = b64
        }
    }

    private suspend fun loadCentroid(): FloatArray? = withContext(Dispatchers.IO) {
        val b64 = context.classifierDataStore.data.first()[KEY_CENTROID] ?: return@withContext null
        return@withContext try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val c     = FloatArray(EMBED_DIM) { buf.float }
            spread    = buf.float
            centroid  = c
            c
        } catch (e: Exception) {
            Timber.w(e, "GestureClassifier: failed to load persisted centroid")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Vector math
    // -------------------------------------------------------------------------

    private fun l2Norm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0f || nb == 0f) return 0f
        return dot / sqrt(na * nb)
    }
}
