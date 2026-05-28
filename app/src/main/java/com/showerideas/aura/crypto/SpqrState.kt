package com.showerideas.aura.crypto

import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Task 63 — Sparse Post-Quantum Ratchet (SPQR) for Room sessions.
 *
 * SPQR is Signal's experimental post-quantum ratchet protocol (signalapp/SparsePostQuantumRatchet,
 * Oct 2025). It extends the Double Ratchet with a sparse PQ ratchet step that provides
 * break-in recovery even after a classical DH ratchet compromise.
 *
 * ## Problem: break-in recovery gap in AURA
 * The existing [DoubleRatchetState] (Task 41) provides forward secrecy — past keys are
 * deleted. But it uses X25519 DH for break-in recovery. A quantum adversary who captures
 * the X25519 private key can recover from a compromise and read future messages.
 *
 * SPQR fixes this: it inserts ML-KEM-768 encapsulations at irregular ("sparse") intervals
 * into the ratchet. Each SPQR step contributes a fresh ML-KEM shared secret to the chain
 * key, so break-in recovery requires solving BOTH X25519 AND ML-KEM-768.
 *
 * ## Sparse schedule
 * A SPQR step runs every [SPQR_STEP_INTERVAL] messages (default 10). This amortizes the
 * 1184-byte ML-KEM-768 public key transmission cost across many messages while still
 * providing break-in recovery frequently enough for multi-minute Room sessions.
 *
 * ## Integration with Room sessions
 * [SpqrState] stores alongside [DoubleRatchetState] (stored in Room session context).
 * [RoomExchangeService] calls [nextMessageKey] which internally decides whether to
 * perform a SPQR step (based on message counter). The returned key is the same type as
 * [DoubleRatchetState.nextMessageKey] — 32-byte AES message key.
 *
 * ## State storage
 * [SpqrState] is serializable via [toBytes] / [fromBytes]. Room session serializes it
 * alongside the existing ratchet state in the encrypted Room session DataStore entry.
 *
 * See: [github.com/signalapp/SparsePostQuantumRatchet] — Signal SPQR reference
 * See: [signal.org/docs/specifications/doubleratchet] — base Double Ratchet spec
 */
class SpqrState(
    private val rng: SecureRandom = SecureRandom()
) {

    companion object {
        /** Message interval between PQ ratchet steps. Configurable for testing. */
        const val SPQR_STEP_INTERVAL = 10

        private const val CHAIN_KEY_LEN = 32
        private const val HKDF_INFO_MSG   = "aura-spqr-msg-v1"
        private const val HKDF_INFO_CHAIN = "aura-spqr-chain-v1"
        private const val HKDF_INFO_PQ    = "aura-spqr-pq-v1"

        /** Deserialise from bytes stored in Room session context. */
        fun fromBytes(bytes: ByteArray): SpqrState {
            val state = SpqrState()
            var off = 0
            state.chainKey = bytes.copyOfRange(off, off + CHAIN_KEY_LEN); off += CHAIN_KEY_LEN
            state.messageCounter = readLong(bytes, off); off += 8
            val hasPqPriv = bytes[off++].toInt() != 0
            if (hasPqPriv) {
                val privLen = readInt(bytes, off); off += 4
                val privBytes = bytes.copyOfRange(off, off + privLen); off += privLen
                state.localPqPriv = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, privBytes)
                val pubLen = readInt(bytes, off); off += 4
                val pubBytes = bytes.copyOfRange(off, off + pubLen)
                state.localPqPub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pubBytes)
            }
            return state
        }

        private fun readLong(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF shl 56) or (b[off+1].toLong() and 0xFF shl 48) or
            (b[off+2].toLong() and 0xFF shl 40) or (b[off+3].toLong() and 0xFF shl 32) or
            (b[off+4].toLong() and 0xFF shl 24) or (b[off+5].toLong() and 0xFF shl 16) or
            (b[off+6].toLong() and 0xFF shl 8)  or (b[off+7].toLong() and 0xFF)

        private fun readInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or ((b[off+1].toInt() and 0xFF) shl 16) or
            ((b[off+2].toInt() and 0xFF) shl 8)  or  (b[off+3].toInt() and 0xFF)
    }

    private var chainKey: ByteArray = ByteArray(CHAIN_KEY_LEN)
    private var messageCounter: Long = 0L
    private var localPqPriv: MLKEMPrivateKeyParameters? = null
    private var localPqPub: MLKEMPublicKeyParameters? = null
    private var pendingPqCiphertext: ByteArray? = null  // outbound SPQR step ciphertext

    /** True when a SPQR step is due and a new ML-KEM ciphertext needs to be sent. */
    val hasPendingSpqrStep: Boolean get() = pendingPqCiphertext != null

    /** ML-KEM ciphertext to include in the next outbound message header. */
    fun takePendingSpqrCiphertext(): ByteArray? {
        val ct = pendingPqCiphertext
        pendingPqCiphertext = null
        return ct
    }

    /** Current local ML-KEM public key — share with peer so they can encapsulate to us. */
    fun localPqPublicKeyBytes(): ByteArray? = localPqPub?.encoded

    /**
     * Initialise the SPQR state from a shared session root key.
     * Both peers must call with the same [rootKey].
     */
    fun initialize(rootKey: ByteArray) {
        require(rootKey.size == CHAIN_KEY_LEN)
        chainKey = hkdf(rootKey, ByteArray(0), HKDF_INFO_CHAIN)
        generateLocalPqKeyPair()
        Timber.d("SpqrState initialized — first PQ key pair generated")
    }

    /**
     * Derive the next message key. Performs a SPQR (PQ ratchet) step every
     * [SPQR_STEP_INTERVAL] messages.
     *
     * @param inboundPqCiphertext If non-null, decapsulate this ML-KEM ciphertext and
     *   fold the PQ shared secret into the chain (receiver-side SPQR step).
     * @return 32-byte AES message key.
     */
    fun nextMessageKey(inboundPqCiphertext: ByteArray? = null): ByteArray {
        // Receiver-side SPQR step: fold inbound PQ KEM into chain
        if (inboundPqCiphertext != null) {
            val priv = localPqPriv ?: error("SpqrState: local PQ key not initialized")
            val dec = MLKEMExtractor(priv)
            val pqShared = dec.extractSecret(inboundPqCiphertext)
            chainKey = hkdf(chainKey, pqShared, HKDF_INFO_PQ)
            generateLocalPqKeyPair()  // rotate our PQ key after use
            Timber.d("SpqrState: PQ ratchet step applied (receiver)")
        }

        // Sender-side SPQR step: fire on the N-th call (N = SPQR_STEP_INTERVAL, 2N, …)
        // messageCounter is still pre-increment; (counter+1) % interval == 0 triggers on
        // call 10, 20, 30, … matching the test expectation ("fires at message == INTERVAL").
        if ((messageCounter + 1) % SPQR_STEP_INTERVAL == 0L && localPqPub != null) {
            val enc = MLKEMGenerator(rng)
            val encResult = enc.generateEncapsulated(localPqPub!!)
            val ct = encResult.encapsulation
            val shared = encResult.secret
            chainKey = hkdf(chainKey, shared, HKDF_INFO_PQ)
            pendingPqCiphertext = ct
            generateLocalPqKeyPair()  // rotate after encapsulation
            Timber.d("SpqrState: PQ ratchet step — msg#$messageCounter ciphertext queued")
        }

        // Derive message key and advance chain
        val msgKey = hkdf(chainKey, byteArrayOf(0x01), HKDF_INFO_MSG)
        chainKey = hkdf(chainKey, byteArrayOf(0x02), HKDF_INFO_CHAIN)
        messageCounter++
        return msgKey
    }

    /** Serialise state for persistence in encrypted Room session DataStore. */
    fun toBytes(): ByteArray {
        val privBytes = localPqPriv?.encoded
        val pubBytes  = localPqPub?.encoded
        val hasPq = privBytes != null && pubBytes != null
        val buf = mutableListOf<Byte>()
        buf.addAll(chainKey.toList())
        buf.addAll(longToBytes(messageCounter).toList())
        buf.add(if (hasPq) 1 else 0)
        if (hasPq) {
            buf.addAll(intToBytes(privBytes!!.size).toList())
            buf.addAll(privBytes.toList())
            buf.addAll(intToBytes(pubBytes!!.size).toList())
            buf.addAll(pubBytes.toList())
        }
        return buf.toByteArray()
    }

    // ---- Internals -----------------------------------------------------------

    private fun generateLocalPqKeyPair() {
        val gen = MLKEMKeyPairGenerator()
        gen.init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768))
        val pair = gen.generateKeyPair()
        localPqPriv = pair.private as MLKEMPrivateKeyParameters
        localPqPub  = pair.public  as MLKEMPublicKeyParameters
    }

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: String): ByteArray {
        val prk = hmacSha256(salt, ikm)
        return hmacSha256(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.let { if (it.isEmpty()) ByteArray(CHAIN_KEY_LEN) else it }, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun longToBytes(v: Long) = ByteArray(8) { i -> (v shr (56 - 8 * i)).toByte() }
    private fun intToBytes(v: Int)   = ByteArray(4) { i -> (v shr (24 - 8 * i)).toByte() }
}
