package com.showerideas.aura.identity

import com.showerideas.aura.model.Profile
import timber.log.Timber
import java.time.Instant
import java.util.UUID

/**
 * Task 51 — W3C Verifiable Credentials for AURA profiles.
 *
 * Models an AURA contact profile as a W3C Verifiable Credential (VC 2.0 spec).
 * The credential subject is the AURA profile (name, avatar hash, exchange count).
 * The proof is a JsonWebSignature2020 over the canonical JSON serialisation.
 *
 * ## Why W3C VCs
 * W3C VCs provide a standardized, interoperable format for identity claims that
 * can be presented across ecosystems (OpenID4VP in Task 60, ISO mdoc in Task 61).
 * Rather than a proprietary AURA card format, each profile becomes a VC that any
 * W3C-compliant verifier can check.
 *
 * ## DID method: did:key
 * The issuer and subject DIDs use `did:key` — a DID method that encodes the public
 * key directly in the DID, requiring no registry or network lookup. AURA derives
 * the `did:key` from the P-256 identity key:
 * ```
 * did:key:zDnae<multibase-encoded P-256 compressed pubkey>
 * ```
 * `z` prefix = base58btc encoding; `0x1200` multicodec prefix for P-256 compressed key.
 *
 * ## Credential format (JSON-LD compatible)
 * ```json
 * {
 *   "@context": ["https://www.w3.org/ns/credentials/v2", "https://aura.id/context/v1"],
 *   "id": "urn:uuid:<random>",
 *   "type": ["VerifiableCredential", "AuraProfileCredential"],
 *   "issuer": "did:key:z...",
 *   "validFrom": "2026-05-26T...",
 *   "validUntil": "2026-08-24T...",
 *   "credentialSubject": {
 *     "id": "did:key:z...",
 *     "displayName": "...",
 *     "avatarSha256": "...",
 *     "exchangeCount": 42,
 *     "auraVersion": "3.3.0"
 *   },
 *   "proof": { ... JsonWebSignature2020 ... }
 * }
 * ```
 *
 * See: [w3.org/TR/vc-data-model-2.0]
 * See: [w3c-ccg.github.io/di-jws2020] — JsonWebSignature2020
 * See: [w3c-ccg.github.io/did-method-key] — did:key method
 */
data class VerifiableCredential(
    val id: String,
    val context: List<String>,
    val type: List<String>,
    val issuerDid: String,
    val subjectDid: String,
    val validFrom: Instant,
    val validUntil: Instant,
    val credentialSubject: AuraProfileSubject,
    val proof: JsonWebSignature2020Proof?
) {

    /** JSON-LD serialisation (compact, not pretty) */
    fun toJsonString(): String = buildString {
        append("{")
        append("\"@context\":${context.toJsonArray()},")
        append("\"id\":\"$id\",")
        append("\"type\":${type.toJsonArray()},")
        append("\"issuer\":\"$issuerDid\",")
        append("\"validFrom\":\"$validFrom\",")
        append("\"validUntil\":\"$validUntil\",")
        append("\"credentialSubject\":{")
        append("\"id\":\"$subjectDid\",")
        append("\"displayName\":\"${credentialSubject.displayName}\",")
        append("\"avatarSha256\":\"${credentialSubject.avatarSha256}\",")
        append("\"profileVersion\":${credentialSubject.profileVersion},")
        append("\"auraVersion\":\"${credentialSubject.auraVersion}\"")
        append("}")
        if (proof != null) {
            append(",\"proof\":{")
            append("\"type\":\"JsonWebSignature2020\",")
            append("\"created\":\"${proof.created}\",")
            append("\"verificationMethod\":\"${proof.verificationMethod}\",")
            append("\"proofPurpose\":\"${proof.proofPurpose}\",")
            append("\"jws\":\"${proof.jws}\"")
            append("}")
        }
        append("}")
    }

    private fun List<String>.toJsonArray() =
        "[${joinToString(",") { "\"$it\"" }}]"

    companion object {
        const val CONTEXT_W3C   = "https://www.w3.org/ns/credentials/v2"
        const val CONTEXT_AURA  = "https://aura.id/context/v1"
        const val TYPE_VC       = "VerifiableCredential"
        const val TYPE_AURA     = "AuraProfileCredential"
        /** Validity period: 90 days */
        private const val VALIDITY_SECONDS = 90L * 24 * 3600

        /** Build an unsigned VC from an AURA [Profile]. Proof added separately by [VcIssuer]. */
        fun fromProfile(profile: Profile, did: String): VerifiableCredential {
            val now = Instant.now()
            return VerifiableCredential(
                id = "urn:uuid:${UUID.randomUUID()}",
                context = listOf(CONTEXT_W3C, CONTEXT_AURA),
                type    = listOf(TYPE_VC, TYPE_AURA),
                issuerDid = did,
                subjectDid = did,
                validFrom = now,
                validUntil = now.plusSeconds(VALIDITY_SECONDS),
                credentialSubject = AuraProfileSubject(
                    displayName   = profile.displayName,
                    avatarSha256  = profile.avatarUri.ifBlank { "" },
                    profileVersion = profile.version,
                    auraVersion   = "3.3.0"
                ),
                proof = null
            ).also { Timber.d("VC created for DID=$did") }
        }
    }
}

/** W3C VC credential subject for an AURA profile. */
data class AuraProfileSubject(
    val displayName: String,
    val avatarSha256: String,
    val profileVersion: Int,
    val auraVersion: String
)

/** JsonWebSignature2020 proof block. */
data class JsonWebSignature2020Proof(
    val type: String = "JsonWebSignature2020",
    val created: Instant,
    val verificationMethod: String,  // did:key#key-1
    val proofPurpose: String = "assertionMethod",
    val jws: String                  // base64url(header.signature), payload omitted (detached)
)
