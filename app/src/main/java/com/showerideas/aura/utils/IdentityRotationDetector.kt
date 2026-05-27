package com.showerideas.aura.utils

import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.model.KnownPeer
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects when a re-connecting peer presents a different identity key hash than
 * the one stored in their [com.showerideas.aura.model.Contact] record.
 *
 * Trust Model: TOFU (Trust On First Use)
 * On the first exchange with a peer, AURA records their identity key hash in
 * [com.showerideas.aura.model.Contact.identityKeyHash]. On all subsequent sessions,
 * the peer must present the same key. If the key changes, one of three things happened:
 *
 *   1. **App reinstall** — the peer uninstalled and reinstalled AURA, generating a
 *      new device identity key. This is benign and common.
 *   2. **Device change** — the peer got a new phone.
 *   3. **MITM / key substitution** — an attacker is presenting a different key and
 *      intercepting the exchange. This is the critical case.
 *
 * This detector surfaces all three to the UI so the user can decide whether to trust
 * the new key. The SAS (Short Authentication String — see [SasVerifier]) provides an
 * additional out-of-band check the user can perform if they suspect case 3.
 *
 * Usage in NearbyExchangeService
 * After receiving the peer's ECDSA identity public key in the HELLO handshake,
 * compute `incomingHash = CryptoUtils.identityKeyHash(peerIdentityKey)`, then call:
 * ```kotlin
 * val event = identityRotationDetector.check(peerName, incomingHash)
 * when (event) {
 *     is RotationEvent.KeyRotated -> { /* warn user, block or re-verify */ }
 *     else -> { /* proceed normally */ }
 * }
 * ```
 *
 * Thread safety
 * [check] is a suspend function — always call from a coroutine.
 */
@Singleton
class IdentityRotationDetector @Inject constructor(
    private val contactRepository: ContactRepository
) {

    // Result type

    sealed class RotationEvent {

        /** First time we see this peer — no stored identity to compare against. */
        object FirstContact : RotationEvent()

        /**
         * Peer's identity key matches the stored hash — no change detected.
         *
         * @param storedHash  SHA-256 Base64 fingerprint that was stored previously.
         */
        data class KeyMatches(val storedHash: String) : RotationEvent()

        /**
         * Peer's identity key hash differs from the stored record.
         *
         * The UI should display a prominent warning before proceeding:
         * _"[Name]'s identity has changed. Verify by comparing the code below
         * before exchanging contact info."_
         *
         * @param storedHash   Previously trusted fingerprint.
         * @param incomingHash New fingerprint presented in this session.
         */
        data class KeyRotated(
            val storedHash: String,
            val incomingHash: String
        ) : RotationEvent() {
            /**
             * Human-readable diff showing the first 12 hex chars of each fingerprint.
             * Suitable for inclusion in a warning dialog body.
             */
            val shortDiff: String
                get() = "was: …${storedHash.take(12)}\n" +
                        "now: …${incomingHash.take(12)}"
        }
    }

    // Public API

    /**
     * Check whether [incomingKeyHash] for a known peer matches their stored contact.
     *
     * Lookup is by [displayName]. If multiple contacts share the same display name,
     * the first one whose [identityKeyHash] is non-null is used — uncommon in practice
     * (display names within a user's personal contacts are usually unique).
     *
     * @param displayName    Display name sent in the peer's HELLO message.
     * @param incomingKeyHash SHA-256 Base64 fingerprint from [CryptoUtils.identityKeyHash].
     * @return [RotationEvent] describing the comparison result.
     */
    suspend fun check(displayName: String, incomingKeyHash: String): RotationEvent {
        val contacts = contactRepository.contacts.firstOrNull().orEmpty()

        val storedContact = contacts.firstOrNull { contact ->
            contact.displayName.equals(displayName, ignoreCase = true) &&
            contact.identityKeyHash != null
        }

        return when {
            storedContact == null -> {
                Timber.d("IdentityRotation: first-contact for '$displayName'")
                RotationEvent.FirstContact
            }
            storedContact.identityKeyHash == incomingKeyHash -> {
                Timber.d("IdentityRotation: key matches for '$displayName'")
                RotationEvent.KeyMatches(incomingKeyHash)
            }
            else -> {
                Timber.w(
                    "IdentityRotation: KEY ROTATED for '$displayName'\n" +
                    "  stored:   ${storedContact.identityKeyHash}\n" +
                    "  incoming: $incomingKeyHash"
                )
                RotationEvent.KeyRotated(
                    storedHash   = storedContact.identityKeyHash!!,
                    incomingHash = incomingKeyHash
                )
            }
        }
    }

    /**
     * Check by raw [identityKeyHash] string rather than display name.
     * Useful when the display name is not yet known (early handshake stage).
     *
     * Returns [RotationEvent.FirstContact] if no contact with [storedHash] exists,
     * [RotationEvent.KeyMatches] if they are equal, or [RotationEvent.KeyRotated]
     * if there is a contact with [storedHash] that now presents [incomingHash].
     */
    suspend fun checkByStoredHash(
        storedHash: String,
        incomingHash: String
    ): RotationEvent {
        if (storedHash == incomingHash) return RotationEvent.KeyMatches(incomingHash)

        val contacts = contactRepository.contacts.firstOrNull().orEmpty()
        val match = contacts.any { it.identityKeyHash == storedHash }

        return if (match) {
            Timber.w("IdentityRotation: hash mismatch — stored=$storedHash incoming=$incomingHash")
            RotationEvent.KeyRotated(storedHash, incomingHash)
        } else {
            RotationEvent.FirstContact
        }
    }

    // Companion — pure static helpers (JVM-unit-testable, no coroutine needed)

    companion object {
        /**
         * Returns true if the peer's stored Base64 public key differs from
         * [incomingBase64Key], indicating a potential key rotation or substitution.
         *
         * This is a side-effect-free equality check — callers use it for early
         * filtering before the full suspend [check] / [checkByStoredHash] calls.
         * Testable in JVM unit tests without any Android or coroutine dependencies.
         */
        fun hasKeyChanged(peer: KnownPeer, incomingBase64Key: String): Boolean =
            peer.identityPublicKeyBase64 != incomingBase64Key
    }
}
