package com.showerideas.aura.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 56 — Noise_XX channel wrapper.
 *
 * Wraps [NoiseHandshakeState] to provide a complete Noise_XX channel:
 * - Static key pair management per session
 * - Handshake message sequencing
 * - Transport encrypt/decrypt after completion
 *
 * ## Integration with AURA transport stack
 * `NoiseChannel` replaces the custom HKDF session token (Task 3) for point-to-point
 * connections. It is negotiated in [HybridKemEngine] as a capability bit in the v9
 * HELLO message. When both peers advertise `NOISE_XX` in their capability set, the
 * session uses Noise_XX instead of the older bespoke ECDH handshake.
 *
 * ## Key reuse policy
 * A fresh Noise_XX channel (and therefore fresh ephemeral keys) is created per exchange
 * session. Static keys are derived from the AURA identity key (Task 48 HybridIdentityKey)
 * as a stable session-layer identity anchor for TOFU verification.
 *
 * ## TOFU binding
 * After handshake, [remoteStaticPublicKey] is compared against [KnownPeerRepository].
 * A first-ever peer shows the SAS verification dialog; subsequent contacts verify silently.
 *
 * See: [noiseprotocol.org/noise.html#the-xx-pattern]
 */
@Singleton
class NoiseChannel @Inject constructor() {

    /** Create a Noise_XX initiator channel with a given static key pair. */
    fun createInitiator(
        staticPriv: X25519PrivateKeyParameters,
        staticPub: X25519PublicKeyParameters,
        rng: SecureRandom = SecureRandom()
    ): NoiseHandshakeState = NoiseHandshakeState(
        isInitiator   = true,
        localStaticPriv = staticPriv,
        localStaticPub  = staticPub,
        rng = rng
    ).also { Timber.d("NoiseChannel: initiator created") }

    /** Create a Noise_XX responder channel with a given static key pair. */
    fun createResponder(
        staticPriv: X25519PrivateKeyParameters,
        staticPub: X25519PublicKeyParameters,
        rng: SecureRandom = SecureRandom()
    ): NoiseHandshakeState = NoiseHandshakeState(
        isInitiator   = false,
        localStaticPriv = staticPriv,
        localStaticPub  = staticPub,
        rng = rng
    ).also { Timber.d("NoiseChannel: responder created") }
}
