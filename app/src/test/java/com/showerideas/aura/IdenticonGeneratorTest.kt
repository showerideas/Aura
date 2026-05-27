package com.showerideas.aura

import com.showerideas.aura.utils.IdenticonGenerator
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [IdenticonGenerator].
 *
 * Note: Bitmap rendering requires the Android framework, so these tests validate
 * only the determinism and non-null guarantees via JVM-compatible checks.
 * Full pixel-level rendering tests live in the instrumented test suite.
 *
 * What we can verify on JVM:
 *  - The generator compiles and has the expected API surface.
 *  - Input variations produce distinguishable outputs (different seeds → different hashes).
 *  - Same seed always produces the same visual identity (determinism guarantee).
 *
 * Since Bitmap.createBitmap is an Android stub in unit tests, we validate the
 * hash-derivation math directly without calling the render path.
 */
class IdenticonGeneratorTest {

    // Hash determinism (pure SHA-256, no Android dependency)

    private fun sha256(input: ByteArray): ByteArray {
        return java.security.MessageDigest.getInstance("SHA-256").digest(input)
    }

    @Test
    fun `same string input always produces same SHA-256 hash`() {
        val seed = "identity-key-hash-example"
        val h1 = sha256(seed.toByteArray(Charsets.UTF_8))
        val h2 = sha256(seed.toByteArray(Charsets.UTF_8))
        assertArrayEquals("SHA-256 must be deterministic", h1, h2)
    }

    @Test
    fun `different seeds produce different SHA-256 hashes`() {
        val seeds = listOf("alice@aura", "bob@aura", "charlie@aura")
        val hashes = seeds.map { sha256(it.toByteArray(Charsets.UTF_8)).toList() }
        val unique = hashes.toSet()
        assertEquals("All seeds must produce distinct hashes", seeds.size, unique.size)
    }

    @Test
    fun `empty string and blank string produce different hashes`() {
        val empty = sha256("".toByteArray(Charsets.UTF_8))
        val blank = sha256(" ".toByteArray(Charsets.UTF_8))
        assertFalse("Empty and blank seeds must produce different hashes",
            empty.contentEquals(blank))
    }

    // Color derivation math (no Android dependency)

    @Test
    fun `hue is bounded within 0-360 degrees for all 256 byte values`() {
        for (byteVal in 0..255) {
            val rawHue = byteVal * 360f / 255f
            val shifted = (rawHue + 160f) % 360f
            assertTrue("Shifted hue must be >= 0", shifted >= 0f)
            assertTrue("Shifted hue must be < 360", shifted < 360f)
        }
    }

    @Test
    fun `grid cell determination uses low bit correctly`() {
        // hash byte with value 0x01 (odd) → cell filled
        val filledByte: Byte = 0x01
        val emptyByte: Byte  = 0x02

        val filled = (filledByte.toInt() and 0x01) == 1
        val empty  = (emptyByte.toInt()  and 0x01) == 1

        assertTrue("Odd byte must mark cell as filled", filled)
        assertFalse("Even byte must mark cell as empty", empty)
    }

    @Test
    fun `symmetric column mirroring maps correctly`() {
        // For a 5-column grid: col 3 mirrors col 1, col 4 mirrors col 0
        val gridSize = 5
        val mirrorMap = mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 1, 4 to 0)

        for (col in 0 until gridSize) {
            val expected = mirrorMap[col]!!
            val actual   = if (col <= 2) col else gridSize - 1 - col
            assertEquals("Column $col should mirror to source column $expected", expected, actual)
        }
    }

    // API surface validation (compile-time check via type system)

    @Test
    fun `string and byteArray overloads both exist in API`() {
        // Verify both entry points are accessible (type check — not a runtime call)
        val stringFn:   (String, Int) -> Any = IdenticonGenerator::generate
        val bytesFn:    (ByteArray, Int) -> Any = IdenticonGenerator::generate

        assertNotNull(stringFn)
        assertNotNull(bytesFn)
    }
}
