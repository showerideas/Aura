package com.showerideas.aura

import com.showerideas.aura.utils.PayloadValidator
import com.showerideas.aura.utils.PayloadValidator.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * unit tests for [PayloadValidator]. The validator is the entire
 * replay-protection contract — if these pass, the wire-level integration
 * in NearbyExchangeService only has to forward the result. JVM-only;
 * no Android dependencies.
 */
class ReplayProtectionTest {

    private val now = 1_700_000_000_000L // fixed reference timestamp

    @Before
    fun reset() {
        // Each test starts with a fresh nonce cache so cross-contamination
        // between tests is impossible.
        PayloadValidator.resetForTesting()
    }

    @Test
    fun `fresh payload with timestamp and nonce returns Ok`() {
        val map = mapOf(
            "_ts" to now.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "displayName" to "Ada"
        )
        assertEquals(ValidationResult.Ok, PayloadValidator.validateProfilePayload(map, now))
    }

    @Test
    fun `missing timestamp is rejected`() {
        val map = mapOf("_nonce" to UUID.randomUUID().toString())
        assertEquals(ValidationResult.MissingTimestamp,
            PayloadValidator.validateProfilePayload(map, now))
    }

    @Test
    fun `non-numeric timestamp is treated as missing`() {
        val map = mapOf("_ts" to "not-a-long", "_nonce" to "n")
        assertEquals(ValidationResult.MissingTimestamp,
            PayloadValidator.validateProfilePayload(map, now))
    }

    @Test
    fun `stale timestamp older than the window is rejected`() {
        val staleTs = now - 120_000L // 2 minutes old
        val map = mapOf("_ts" to staleTs.toString(), "_nonce" to "n")
        val result = PayloadValidator.validateProfilePayload(map, now)
        assertTrue("Expected StaleTimestamp, got $result", result is ValidationResult.StaleTimestamp)
        assertEquals(120_000L, (result as ValidationResult.StaleTimestamp).deltaMs)
    }

    @Test
    fun `future timestamp beyond the window is also rejected`() {
        // Symmetric clock-skew check: a payload from the "future" is just as
        // suspicious as one from the distant past.
        val futureTs = now + 120_000L
        val map = mapOf("_ts" to futureTs.toString(), "_nonce" to "n")
        assertTrue(
            PayloadValidator.validateProfilePayload(map, now) is ValidationResult.StaleTimestamp
        )
    }

    @Test
    fun `missing nonce is rejected`() {
        val map = mapOf("_ts" to now.toString())
        assertEquals(ValidationResult.MissingNonce,
            PayloadValidator.validateProfilePayload(map, now))
    }

    @Test
    fun `the same nonce twice is the replay case`() {
        val nonce = UUID.randomUUID().toString()
        val map = mapOf("_ts" to now.toString(), "_nonce" to nonce)
        assertEquals(ValidationResult.Ok,
            PayloadValidator.validateProfilePayload(map, now))
        assertEquals(ValidationResult.ReplayedNonce,
            PayloadValidator.validateProfilePayload(map, now))
    }

    @Test
    fun `unique nonces are independent`() {
        val n1 = UUID.randomUUID().toString()
        val n2 = UUID.randomUUID().toString()
        val m1 = mapOf("_ts" to now.toString(), "_nonce" to n1)
        val m2 = mapOf("_ts" to now.toString(), "_nonce" to n2)
        assertEquals(ValidationResult.Ok, PayloadValidator.validateProfilePayload(m1, now))
        assertEquals(ValidationResult.Ok, PayloadValidator.validateProfilePayload(m2, now))
    }

    @Test
    fun `purgeNonces lets a previously-seen nonce pass again`() {
        val nonce = UUID.randomUUID().toString()
        val map = mapOf("_ts" to now.toString(), "_nonce" to nonce)
        assertEquals(ValidationResult.Ok, PayloadValidator.validateProfilePayload(map, now))
        PayloadValidator.purgeNonces()
        // After a purge, the same nonce is valid again (different physical
        // session — the cache is intentionally a short-lived dedup window).
        assertEquals(ValidationResult.Ok, PayloadValidator.validateProfilePayload(map, now))
    }

    @Test
    fun `stampOutgoingProfile adds ts and nonce when absent`() {
        val map = mutableMapOf<String, String>("displayName" to "Ada")
        PayloadValidator.stampOutgoingProfile(map)
        assertTrue("_ts" in map)
        assertTrue("_nonce" in map)
        assertTrue(map["_ts"]!!.toLong() > 0L)
    }

    @Test
    fun `stampOutgoingProfile is idempotent on already-stamped maps`() {
        val map = mutableMapOf("_ts" to "12345", "_nonce" to "fixed")
        PayloadValidator.stampOutgoingProfile(map)
        assertEquals("12345", map["_ts"])
        assertEquals("fixed", map["_nonce"])
    }

    // -----------------------------------------------------------------------
    // per-field length cap tests
    // -----------------------------------------------------------------------

    @Test
    fun `field at exactly the cap limit is accepted`() {
        val maxStr = "A".repeat(PayloadValidator.MAX_FIELD_LENGTH)
        val map = mapOf(
            "_ts" to now.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "displayName" to maxStr
        )
        assertEquals(ValidationResult.Ok, PayloadValidator.validateProfilePayload(map, now))
    }

    @Test
    fun `displayName one character over the cap is rejected`() {
        val tooLong = "A".repeat(PayloadValidator.MAX_FIELD_LENGTH + 1)
        val map = mapOf(
            "_ts" to now.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "displayName" to tooLong
        )
        val result = PayloadValidator.validateProfilePayload(map, now)
        assertTrue("Expected FieldTooLong, got $result",
            result is ValidationResult.FieldTooLong)
        assertEquals("displayName", (result as ValidationResult.FieldTooLong).field)
        assertEquals(tooLong.length, result.length)
    }

    @Test
    fun `bio field over the cap is rejected`() {
        // "note" was the old dead key; wire format uses "bio". Verify bio is capped.
        val tooLong = "x".repeat(PayloadValidator.MAX_FIELD_LENGTH + 1)
        val map = mapOf(
            "_ts" to now.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "bio" to tooLong
        )
        assertTrue(
            PayloadValidator.validateProfilePayload(map, now) is ValidationResult.FieldTooLong
        )
    }

    @Test
    fun `all newly-added profile fields are capped (company, title, website)`() {
        // Ensure heap-exhaustion fix covers every field in Profile.toShareableMap().
        val tooLong = "z".repeat(PayloadValidator.MAX_FIELD_LENGTH + 1)
        for (field in listOf("company", "title", "website")) {
            PayloadValidator.resetForTesting()
            val map = mapOf(
                "_ts" to now.toString(),
                "_nonce" to UUID.randomUUID().toString(),
                field to tooLong
            )
            val result = PayloadValidator.validateProfilePayload(map, now)
            assertTrue(
                "Expected FieldTooLong for '$field' > ${PayloadValidator.MAX_FIELD_LENGTH} chars, got $result",
                result is ValidationResult.FieldTooLong
            )
        }
    }

    @Test
    fun `internal underscore fields are not capped by field-length check`() {
        // _ts and _nonce are protocol fields; their content is validated by
        // type (Long / UUID) not length. A 600-char value for an internal
        // field would fail the toLong() or add-to-set step, not this check.
        val map = mapOf(
            "_ts" to now.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "displayName" to "short name"
        )
        assertEquals(ValidationResult.Ok, PayloadValidator.validateProfilePayload(map, now))
    }
}
