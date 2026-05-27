package com.showerideas.aura.utils

import timber.log.Timber
import java.util.Collections
import java.util.UUID

/**
 * replay-attack protection for exchanged profile payloads.
 *
 * A captured ciphertext can be re-played in a future session by anyone who
 * has eavesdropped on the BLE / Wi-Fi P2P channel. AES-GCM stops the
 * attacker from *modifying* the bytes, but they could still re-deliver the
 * same encrypted profile to mint a duplicate contact row.
 *
 * Mitigation: every outgoing profile carries two internal fields stripped
 * before the Contact is saved:
 *  - `_ts`     wall-clock send time in ms. Rejected if more than
 *              [MAX_AGE_MS] apart from the local clock (clock skew tolerance).
 *  - `_nonce`  random per-send UUID. Stored in [recentNonces] on receipt;
 *              a second presentation within the cache window is rejected.
 *
 * The nonce cache is purged every [PURGE_INTERVAL_MS] so the in-memory
 * footprint is bounded.
 */
object PayloadValidator {

    /** Allowed clock-skew window in milliseconds. */
    const val MAX_AGE_MS: Long = 60_000L

    /** How often [purgeNonces] gets called by the service-side coroutine. */
    const val PURGE_INTERVAL_MS: Long = 300_000L // 5 minutes

    /**
     * Thread-safe deduplication set. Bounded to [MAX_NONCE_CACHE_SIZE] to
     * defend against heap-exhaustion under a sustained flood attack: a rogue
     * peer (or room-mode host) that sends thousands of unique-nonce profiles
     * per minute would otherwise grow this set without bound until the 5-minute
     * purge cycle fires. When the cap is hit we force-purge and log a warning.
     */
    private val recentNonces: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    /**
     * Upper bound on the nonce deduplication cache between purge cycles.
     * 1 000 covers ~16 hours of exchanges at one per minute — well above any
     * legitimate room session. Exceeding this is a strong indicator of a flood.
     */
    private const val MAX_NONCE_CACHE_SIZE = 1_000

    /**
     * per-field maximum character lengths.
     *
     * A crafted peer can complete the challenge/response (or in a first-meet
     * scenario substitute their own identity key via TOFU) and then send a
     * profile whose fields contain millions of characters. The AES-GCM
     * decryption and Gson parsing both operate on the full string, so
     * allocating a 10 MB String is possible within the Nearby BYTES limit.
     *
     * These caps are enforced in [validateProfilePayload] on the user-visible
     * fields. Internal fields (_ts, _nonce) are short by construction and are
     * not capped here.
     */
    const val MAX_FIELD_LENGTH: Int = 500

    /**
     * All user-visible string fields that a peer can populate in their profile map.
     * Every key from [com.showerideas.aura.model.Profile.toShareableMap] is listed here
     * so a crafted peer cannot cause unbounded heap allocation by sending a multi-MB
     * value in any field that arrives through the Nearby Connections wire format.
     * Previously "note" was listed but it is a dead key — the wire format uses "bio".
     */
    private val CAPPED_FIELDS = setOf(
        "displayName", "phone", "email",
        "company", "title", "website", "bio"
    )

    sealed class ValidationResult {
        object Ok : ValidationResult()
        object MissingTimestamp : ValidationResult()
        data class StaleTimestamp(val deltaMs: Long) : ValidationResult()
        object MissingNonce : ValidationResult()
        object ReplayedNonce : ValidationResult()
        data class FieldTooLong(val field: String, val length: Int) : ValidationResult()
    }

    /**
     * Validate an incoming profile map. Mutates [recentNonces] on success.
     * The [nowMs] override exists so tests can pin time without juggling
     * clocks.
     */
    fun validateProfilePayload(
        map: Map<String, String>,
        nowMs: Long = System.currentTimeMillis()
    ): ValidationResult {
        val ts = map["_ts"]?.toLongOrNull() ?: return ValidationResult.MissingTimestamp
        val delta = nowMs - ts
        if (kotlin.math.abs(delta) > MAX_AGE_MS) return ValidationResult.StaleTimestamp(delta)
        val nonce = map["_nonce"] ?: return ValidationResult.MissingNonce
        // .add returns false if the nonce was already present -> replay.
        if (!recentNonces.add(nonce)) return ValidationResult.ReplayedNonce
        // Flood-attack guard: if the cache has grown past the cap, force-purge
        // immediately. Legitimate sessions produce at most a handful of nonces;
        // hitting MAX_NONCE_CACHE_SIZE is only possible under active flooding.
        if (recentNonces.size > MAX_NONCE_CACHE_SIZE) {
            Timber.w("Nonce cache exceeded $MAX_NONCE_CACHE_SIZE entries — force-purging (flood attack?)")
            purgeNonces()
        }
        // cap individual string fields to prevent a
        // crafted peer from allocating unbounded heap via long field values.
        for (field in CAPPED_FIELDS) {
            val value = map[field] ?: continue
            if (value.length > MAX_FIELD_LENGTH) {
                return ValidationResult.FieldTooLong(field, value.length)
            }
        }
        return ValidationResult.Ok
    }

    /** Wipe the nonce cache. Called from the service's periodic purge job. */
    fun purgeNonces() {
        val size = recentNonces.size
        recentNonces.clear()
        Timber.d("Nonce cache cleared (was $size)")
    }

    /**
     * Stamp an outgoing profile map with `_ts` + `_nonce`. Skips fields
     * already present so a caller can stamp once at the top of a send
     * helper without worrying about double-stamping.
     */
    fun stampOutgoingProfile(map: MutableMap<String, String>) {
        if ("_ts" !in map) map["_ts"] = System.currentTimeMillis().toString()
        if ("_nonce" !in map) map["_nonce"] = UUID.randomUUID().toString()
    }

    /** Test-only helper. Clears the cache without logging. */
    internal fun resetForTesting() {
        recentNonces.clear()
    }
}

