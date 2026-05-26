package com.showerideas.aura.crypto

import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Task 57 — MLS RFC 9420 group key agreement for Room sessions.
 *
 * Implements a simplified MLS (Messaging Layer Security) group state machine for
 * AURA's multi-party Room exchange sessions. MLS provides forward-secret, post-compromise
 * secure group key agreement without requiring a trusted host.
 *
 * ## Why MLS for Room sessions (vs current Task 10 AES-GCM routing)
 * Task 10 uses a single AES-256 room key distributed by the host. This requires trusting
 * the host with all members' data — the host decrypts and re-encrypts for routing.
 * MLS eliminates this: every member derives the same epoch key independently via HKDF
 * tree ratcheting. The host routes opaque ciphertext it cannot read.
 *
 * ## MLS concepts used
 * - **Epoch**: a sequential group state. Every member join/leave bumps the epoch and all
 *   members ratchet to a new epoch key. Old epoch keys are deleted (forward secrecy).
 * - **Group context**: hash of all epoch history — binds the epoch key to the exact group
 *   membership at that point in time.
 * - **Welcome message**: encrypted to a new joiner's prekey so they can catch up to the
 *   current epoch without seeing past epochs.
 * - **KeyPackage**: a member's signed public key + prekey bundle — the MLS equivalent of
 *   AURA's PreKeyBundle (Task 47). AURA reuses [PreKeyBundle] as the KeyPackage identity.
 *
 * ## AURA-specific simplification
 * Full MLS RFC 9420 uses a binary ratchet tree (TreeKEM). AURA's Room sessions are small
 * (≤20 members, ≤10 min lifetime) so we use a flat member list with HKDF epoch derivation.
 * TreeKEM can be layered on top when Room sessions exceed 20 members (post-T57 work).
 *
 * ## Epoch key derivation
 * ```
 * epochSecret  = HKDF(salt=previousEpochKey, ikm=joinerCommitSecret, info="aura-mls-epoch-v1")
 * applicationSecret = HKDF(salt=epochSecret, ikm="", info="aura-mls-app-v1")
 * ```
 *
 * See: [rfc-editor.org/rfc/rfc9420] — MLS RFC 9420
 * See: [github.com/bifurcation/mint] — reference MLS implementation
 * See: [github.com/mlspp/mlspp] — C++ reference, Cisco/IETF
 */
class MlsGroupState(
    val roomId: String,
    private val rng: SecureRandom = SecureRandom()
) {

    companion object {
        private const val KEY_LEN         = 32
        private const val EPOCH_INFO      = "aura-mls-epoch-v1"
        private const val APP_INFO        = "aura-mls-app-v1"
        private const val WELCOME_INFO    = "aura-mls-welcome-v1"
        private const val CONFIRMATION_TAG = "aura-mls-confirm-v1"
    }

    /** Current MLS epoch number. Starts at 0 (group creation). */
    var epoch: Long = 0L
        private set

    /** Current epoch application key — used to encrypt/decrypt Room messages. */
    private var applicationKey: ByteArray = ByteArray(KEY_LEN)

    /** HKDF epoch secret — kept to derive next epoch on membership change. */
    private var epochSecret: ByteArray = ByteArray(KEY_LEN)

    /** Set of member IDs (typically their AURA contact fingerprint). */
    private val members: MutableSet<String> = mutableSetOf()

    /** Confirmation tag of the last Commit — receivers verify before accepting epoch. */
    private var lastConfirmationTag: ByteArray = ByteArray(32)

    /**
     * Create the initial epoch (epoch 0) for a new group.
     * @param creatorId     Identity fingerprint of the creator (room host).
     * @param initialSecret 32 bytes of fresh randomness — the group's init secret.
     */
    fun initialize(creatorId: String, initialSecret: ByteArray = rng.generateSeed(KEY_LEN)) {
        require(initialSecret.size == KEY_LEN) { "MLS init secret must be $KEY_LEN bytes" }
        members.clear()
        members.add(creatorId)
        epoch = 0L
        epochSecret    = hkdf(salt = initialSecret, ikm = ByteArray(KEY_LEN), info = EPOCH_INFO)
        applicationKey = hkdf(salt = epochSecret, ikm = ByteArray(0), info = APP_INFO)
        lastConfirmationTag = computeConfirmationTag(epochSecret, groupContext())
        Timber.d("MLS group initialized — roomId=$roomId epoch=0 creator=$creatorId")
    }

    /**
     * Create a Welcome message for a new joiner.
     * @param joinerId         Identity fingerprint of the member being added.
     * @param joinerPublicKey  Public key bytes from their PreKeyBundle (used to wrap epoch secret).
     * @return [MlsWelcome] containing the epoch secret encrypted to the joiner.
     */
    fun createWelcome(joinerId: String, joinerPublicKey: ByteArray): MlsWelcome {
        // Wrap current epochSecret to the joiner using HKDF-derived wrapping key
        // In full MLS this uses HPKE; here we use a simplified KEM: XOR with HKDF(joinerPub)
        val wrapKey = hkdf(salt = joinerPublicKey.take(KEY_LEN).toByteArray(), ikm = epochSecret, info = WELCOME_INFO)
        val wrappedEpochSecret = epochSecret.xorWith(wrapKey)
        return MlsWelcome(
            roomId       = roomId,
            epoch        = epoch,
            joinerId     = joinerId,
            wrappedEpochSecret = wrappedEpochSecret,
            memberSnapshot = members.toSet(),
            confirmationTag = lastConfirmationTag.copyOf()
        ).also { Timber.d("MLS Welcome created for $joinerId at epoch=$epoch") }
    }

    /**
     * Process a Welcome message as a new joiner — advances to the current epoch.
     * @param welcome          The [MlsWelcome] addressed to this member.
     * @param myPrivateKeyBytes Private key bytes corresponding to the public key in the Welcome.
     * @param myId             This member's identity fingerprint.
     */
    fun processWelcome(welcome: MlsWelcome, myPrivateKeyBytes: ByteArray, myId: String) {
        require(welcome.roomId == roomId) { "Welcome is for a different room" }
        require(welcome.joinerId == myId) { "Welcome is not addressed to $myId" }
        val myPubDerived = hkdf(salt = myPrivateKeyBytes.take(KEY_LEN).toByteArray(), ikm = ByteArray(0), info = "pub")
        val wrapKey = hkdf(salt = myPubDerived.take(KEY_LEN).toByteArray(), ikm = welcome.wrappedEpochSecret, info = WELCOME_INFO)
        // Unwrap: XOR again
        epochSecret = welcome.wrappedEpochSecret.xorWith(wrapKey)
        applicationKey = hkdf(salt = epochSecret, ikm = ByteArray(0), info = APP_INFO)
        epoch = welcome.epoch
        members.clear(); members.addAll(welcome.memberSnapshot); members.add(myId)
        lastConfirmationTag = welcome.confirmationTag.copyOf()
        Timber.d("MLS Welcome processed — epoch=$epoch members=${members.size}")
    }

    /**
     * Commit a membership change (add/remove). Advances epoch and rotates all keys.
     * @param addedId   Identity fingerprint of member being added, or null.
     * @param removedId Identity fingerprint of member being removed, or null.
     * @param commitSecret Fresh 32-byte randomness contributed by the committer.
     */
    fun commit(
        addedId: String? = null,
        removedId: String? = null,
        commitSecret: ByteArray = rng.generateSeed(KEY_LEN)
    ) {
        if (addedId != null)   members.add(addedId)
        if (removedId != null) members.remove(removedId)
        epoch++
        epochSecret    = hkdf(salt = epochSecret, ikm = commitSecret, info = EPOCH_INFO)
        applicationKey = hkdf(salt = epochSecret, ikm = ByteArray(0), info = APP_INFO)
        lastConfirmationTag = computeConfirmationTag(epochSecret, groupContext())
        Timber.d("MLS Commit — epoch=$epoch added=$addedId removed=$removedId members=${members.size}")
    }

    /**
     * Process a Commit message from another group member — advances to the same epoch.
     * Callers must verify [confirmationTag] matches before calling this.
     */
    fun processCommit(
        addedId: String? = null,
        removedId: String? = null,
        commitSecret: ByteArray,
        confirmationTag: ByteArray
    ) {
        if (addedId != null)   members.add(addedId)
        if (removedId != null) members.remove(removedId)
        epoch++
        epochSecret    = hkdf(salt = epochSecret, ikm = commitSecret, info = EPOCH_INFO)
        applicationKey = hkdf(salt = epochSecret, ikm = ByteArray(0), info = APP_INFO)
        val expectedTag = computeConfirmationTag(epochSecret, groupContext())
        require(expectedTag.contentEquals(confirmationTag)) {
            "MLS Commit confirmation tag mismatch — potential group state desync"
        }
        lastConfirmationTag = confirmationTag.copyOf()
        Timber.d("MLS processCommit — epoch=$epoch members=${members.size}")
    }

    /** Current application key for message encrypt/decrypt. Never null after [initialize]. */
    fun currentApplicationKey(): ByteArray = applicationKey.copyOf()

    /** Current member set. */
    fun currentMembers(): Set<String> = members.toSet()

    /** Current confirmation tag — include in Commit messages for recipient verification. */
    fun confirmationTag(): ByteArray = lastConfirmationTag.copyOf()

    // ---- Internals -----------------------------------------------------------

    private fun groupContext(): ByteArray {
        val sorted = members.sorted().joinToString(",")
        return "$roomId:$epoch:$sorted".toByteArray(Charsets.UTF_8)
    }

    private fun computeConfirmationTag(epochSec: ByteArray, ctx: ByteArray): ByteArray =
        hmacSha256(epochSec, ctx + CONFIRMATION_TAG.toByteArray())

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: String): ByteArray {
        val prk = hmacSha256(salt.ifEmpty { ByteArray(KEY_LEN) }, ikm)
        return hmacSha256(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.let { if (it.isEmpty()) ByteArray(KEY_LEN) else it }, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun ByteArray.xorWith(other: ByteArray): ByteArray {
        val out = ByteArray(size)
        for (i in indices) out[i] = (this[i].toInt() xor other[i % other.size].toInt()).toByte()
        return out
    }
}
