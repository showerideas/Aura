package com.showerideas.aura.identity

import java.util.Base64

import timber.log.Timber
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 60 — OpenID4VP (OpenID for Verifiable Presentations).
 *
 * AURA contacts can share their profile as a W3C Verifiable Presentation (VP) via
 * the OpenID4VP protocol. This enables cross-ecosystem interoperability: any wallet
 * or verifier that speaks OpenID4VP (Google Wallet, Apple Wallet, EU Digital Identity)
 * can receive and verify AURA contact credentials.
 *
 * ## OpenID4VP flow
 * 1. Verifier sends an **Authorization Request** (QR code or deep link) with a `nonce`
 *    and a **Presentation Definition** specifying which credential types it accepts.
 * 2. AURA shows a **field picker** (same UX as the share preset — Task 22): user selects
 *    which fields from the VC to disclose.
 * 3. AURA builds a **Verifiable Presentation** (VP) wrapping the selective disclosure VC.
 * 4. AURA posts the VP as a **VP Token** in the Authorization Response to the verifier's
 *    redirect_uri (or direct_post endpoint for cross-device flows).
 *
 * ## Selective disclosure
 * OpenID4VP with W3C Data Integrity (Task 51) supports selective disclosure by including
 * only the desired credential subject fields in the VP. The original VC proof covers
 * the full credential; the VP proof covers the VP envelope.
 *
 * ## W3C VP format
 * ```json
 * {
 *   "@context": ["https://www.w3.org/ns/credentials/v2"],
 *   "type": ["VerifiablePresentation"],
 *   "id": "urn:uuid:<random>",
 *   "holder": "did:key:z...",
 *   "verifiableCredential": [ <AURA VC from Task 51> ],
 *   "proof": { ...JsonWebSignature2020 over VP... }
 * }
 * ```
 *
 * ## Android DigitalCredentials API
 * Android 15+ provides `android.credentials.GetDigitalCredentialRequest` for native wallet
 * integration. AURA registers as a CredentialProvider and responds to OpenID4VP requests
 * from any app that calls `CredentialManager.getCredential()`. This is the same flow
 * used by Google Wallet for age verification and ID cards.
 *
 * See: [openid.net/specs/openid-4-verifiable-presentations-1_0.html]
 * See: [developer.android.com/identity/digital-credentials]
 * See: [github.com/openid/OpenID4VP] — reference implementations
 */
@Singleton
class VpBuilder @Inject constructor(
    private val vcIssuer: VcIssuer
) {

    companion object {
        const val VP_CONTEXT   = "https://www.w3.org/ns/credentials/v2"
        const val VP_TYPE      = "VerifiablePresentation"
    }

    /**
     * Build an OpenID4VP Authorization Request parameters — for display as QR or deep link.
     * Called by a verifier-side component; included here for reference.
     *
     * @param clientId       Verifier's DID or URL.
     * @param nonce          Fresh random nonce — prevents replay.
     * @param presentationDefinitionId ID of the Presentation Definition the verifier sent.
     * @param redirectUri    Where AURA posts the VP token.
     */
    data class AuthorizationRequestParams(
        val clientId: String,
        val nonce: String,
        val presentationDefinitionId: String,
        val redirectUri: String,
        val responseType: String = "vp_token",
        val responseMode: String = "direct_post"
    )

    /**
     * Build a W3C Verifiable Presentation from one or more VCs.
     *
     * @param vcs          VCs to include (typically one AURA profile VC from Task 51).
     * @param holderDid    DID of the holder (same as issuerDid for self-issued VCs).
     * @param nonce        Nonce from the Authorization Request — binds VP to request.
     * @param holderPriv   P-256 private key for VP proof signature.
     * @param holderPub    P-256 public key for verification method reference.
     * @param disclosedFields Set of field names to include in the disclosure (selective).
     *                        null = include all fields.
     */
    fun buildVp(
        vcs: List<VerifiableCredential>,
        holderDid: String,
        nonce: String,
        holderPriv: ECPrivateKey,
        holderPub: ECPublicKey,
        disclosedFields: Set<String>? = null
    ): VerifiablePresentation {
        val vpId = "urn:uuid:${UUID.randomUUID()}"
        val now  = Instant.now()

        // Selective disclosure: filter VC subject fields if requested
        val disclosedVcs = if (disclosedFields != null) {
            vcs.map { vc -> applySelectiveDisclosure(vc, disclosedFields) }
        } else vcs

        val vp = VerifiablePresentation(
            id     = vpId,
            context = listOf(VP_CONTEXT),
            type   = listOf(VP_TYPE),
            holder = holderDid,
            verifiableCredentials = disclosedVcs,
            nonce  = nonce,
            created = now,
            proof  = null
        )

        val proof = signVp(vp, holderPriv, holderDid, nonce)
        Timber.d("VP built: id=$vpId vc_count=${disclosedVcs.size} fields=$disclosedFields")
        return vp.copy(proof = proof)
    }

    /**
     * Selective disclosure: produce a copy of the VC with only the requested fields
     * in the credential subject. The VC proof still covers the original full subject
     * (honest verifiers are expected to re-verify the VC before trusting the VP).
     */
    private fun applySelectiveDisclosure(
        vc: VerifiableCredential,
        disclosedFields: Set<String>
    ): VerifiableCredential {
        val subject = vc.credentialSubject
        val filtered = AuraProfileSubject(
            displayName    = if ("displayName" in disclosedFields) subject.displayName else "[redacted]",
            avatarSha256   = if ("avatarSha256" in disclosedFields) subject.avatarSha256 else "",
            profileVersion = if ("profileVersion" in disclosedFields) subject.profileVersion else 0,
            auraVersion    = subject.auraVersion  // always included
        )
        return vc.copy(credentialSubject = filtered)
    }

    /** Sign a VP with JsonWebSignature2020. */
    private fun signVp(
        vp: VerifiablePresentation,
        priv: ECPrivateKey,
        did: String,
        nonce: String
    ): JsonWebSignature2020Proof {
        val headerJson = """{"alg":"ES256","b64":false,"crit":["b64"]}"""
        val headerB64  = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.toByteArray())
        val vpJson = vp.toJsonString()
        val signedData = "$headerB64.$vpJson$nonce".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withECDSA").also {
            it.initSign(priv, SecureRandom())
            it.update(signedData)
        }.sign()
        // DER → raw 64-byte
        val raw = derToRaw(sig)
        val jws = "$headerB64..${Base64.getUrlEncoder().withoutPadding().encodeToString(raw)}"
        return JsonWebSignature2020Proof(
            created = Instant.now(),
            verificationMethod = "$did#key-1",
            jws = jws
        )
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        val rLen = der[i + 1].toInt() and 0xFF; i += 2
        val r = der.copyOfRange(i, i + rLen); i += rLen
        val sLen = der[i + 1].toInt() and 0xFF; i += 2
        val s = der.copyOfRange(i, i + sLen)
        val raw = ByteArray(64)
        r.copyInto(raw, 32 - r.size.coerceAtMost(32))
        s.copyInto(raw, 64 - s.size.coerceAtMost(32))
        return raw
    }
}

/**
 * W3C Verifiable Presentation wrapping one or more VCs.
 */
data class VerifiablePresentation(
    val id: String,
    val context: List<String>,
    val type: List<String>,
    val holder: String,
    val verifiableCredentials: List<VerifiableCredential>,
    val nonce: String,
    val created: Instant,
    val proof: JsonWebSignature2020Proof?
) {
    fun toJsonString(): String = buildString {
        append("{")
        append("\"@context\":${context.toJsonArray()},")
        append("\"type\":${type.toJsonArray()},")
        append("\"id\":\"$id\",")
        append("\"holder\":\"$holder\",")
        append("\"verifiableCredential\":[${verifiableCredentials.joinToString(",") { it.toJsonString() }}],")
        append("\"nonce\":\"$nonce\"")
        if (proof != null) {
            append(",\"proof\":{")
            append("\"type\":\"JsonWebSignature2020\",")
            append("\"created\":\"${proof.created}\",")
            append("\"verificationMethod\":\"${proof.verificationMethod}\",")
            append("\"jws\":\"${proof.jws}\"")
            append("}")
        }
        append("}")
    }

    private fun List<String>.toJsonArray() = "[${joinToString(",") { "\"$it\"" }}]"
}
