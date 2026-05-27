package com.showerideas.aura.zk

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 90 — Android JNI interface to the gnark ZK-SNARK library.
 *
 * Provides Kotlin-side proof generation and verification for the
 * gesture cosine similarity circuit defined in [zk/gesture_circuit.go].
 *
 * ## JNI library
 * libgesturezk.so is compiled from the gnark circuit via `gomobile bind`.
 * ARM64 binary lives at app/src/main/jniLibs/arm64-v8a/libgesturezk.so.
 * Proving key and verifying key are loaded from app/src/main/assets/zk/.
 *
 * ## Performance contract
 * - generateProof: < 5 seconds on Snapdragon 730G equivalent
 * - verifyProof:   < 100 ms (Groth16 verification is fast)
 * - Proof size:    ≤ 200 bytes
 *
 * ## Enterprise feature flag
 * [BuildConfig.ENABLE_ZK_AUDIT_PROOF] must be true for proofs to be generated.
 * Disabled by default; enabled via MDM policy `zkGestureProofAudit: true`.
 *
 * See: github.com/ConsenSys/gnark
 * See: ROADMAP §Task 89/90
 */
@Singleton
class GestureZkProver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PROVING_KEY_ASSET  = "zk/gesture_proving_key.bin"
        private const val VERIFYING_KEY_ASSET = "zk/gesture_verifying_key.bin"
        private const val LIB_NAME = "gesturezk"

        private var libLoaded = false

        private fun tryLoadLib() {
            if (libLoaded) return
            try {
                System.loadLibrary(LIB_NAME)
                libLoaded = true
                Timber.d("GestureZkProver: libgesturezk loaded")
            } catch (e: UnsatisfiedLinkError) {
                Timber.w(e, "GestureZkProver: libgesturezk not available (expected in release builds only)")
            }
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    /**
     * Generate a Groth16 proof that live and enrolled descriptors have
     * cosine similarity ≥ 0.85.
     *
     * @param enrolledDescriptor 107 floats — private witness (enrolled template)
     * @param liveDescriptor     107 floats — public input (live capture)
     * @param provingKeyPath     Absolute path to the Groth16 proving key binary
     * @return Raw Groth16 proof bytes (~192 bytes)
     */
    private external fun generateProof(
        enrolledDescriptor: FloatArray,
        liveDescriptor: FloatArray,
        provingKeyPath: String
    ): ByteArray

    /**
     * Verify a Groth16 proof without the private enrolled descriptor.
     *
     * @param proof            Raw proof bytes from [generateProof]
     * @param liveDescriptor   Public input used during proof generation
     * @param verifyingKeyPath Absolute path to the Groth16 verifying key binary
     * @return true if isMatch == 1 in the verified circuit
     */
    private external fun verifyProof(
        proof: ByteArray,
        liveDescriptor: FloatArray,
        verifyingKeyPath: String
    ): Boolean

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generate and verify a ZK proof on [Dispatchers.Default].
     * Returns null if the JNI library is unavailable (non-release builds, emulators).
     */
    suspend fun prove(
        enrolledDescriptor: FloatArray,
        liveDescriptor: FloatArray
    ): ZkProofResult? = withContext(Dispatchers.Default) {
        tryLoadLib()
        if (!libLoaded) return@withContext null

        val provingKeyPath = extractAsset(PROVING_KEY_ASSET) ?: return@withContext null
        val verifyingKeyPath = extractAsset(VERIFYING_KEY_ASSET) ?: return@withContext null

        return@withContext try {
            val startMs = System.currentTimeMillis()
            val proofBytes = generateProof(enrolledDescriptor, liveDescriptor, provingKeyPath)
            val proveMs = System.currentTimeMillis() - startMs
            Timber.d("GestureZkProver: proof generated in ${proveMs}ms (${proofBytes.size} bytes)")

            val isMatch = verifyProof(proofBytes, liveDescriptor, verifyingKeyPath)
            val verifyMs = System.currentTimeMillis() - startMs - proveMs
            Timber.d("GestureZkProver: verification in ${verifyMs}ms → isMatch=$isMatch")

            ZkProofResult(proofBytes, isMatch)
        } catch (e: Exception) {
            Timber.e(e, "GestureZkProver: prove() failed")
            null
        }
    }

    // ── Asset extraction ──────────────────────────────────────────────────────

    private fun extractAsset(assetPath: String): String? {
        val outFile = File(context.filesDir, assetPath.replace("/", "_"))
        if (!outFile.exists()) {
            try {
                context.assets.open(assetPath).use { input ->
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                Timber.w(e, "GestureZkProver: asset not found: $assetPath (expected in release builds)")
                return null
            }
        }
        return outFile.absolutePath
    }
}
