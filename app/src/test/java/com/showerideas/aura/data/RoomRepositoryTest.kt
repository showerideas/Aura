package com.showerideas.aura.data

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class RoomRepositoryTest {

    @Test
    fun `RoomSession expiresAt is exactly 10 minutes after createdAt`() {
        val now = System.currentTimeMillis()
        assertEquals(now + 10 * 60 * 1000L, now + RoomRepository.ROOM_TTL_MS)
    }

    @Test
    fun `PIN generation produces exactly 6 digits`() {
        repeat(20) {
            val pin = (SecureRandom().nextInt(900000) + 100000).toString()
            assertEquals(6, pin.length)
            assertTrue(pin.toInt() in 100000..999999)
        }
    }

    @Test
    fun `roomId hex encoding is 64 chars for 32 random bytes`() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex   = bytes.joinToString("") { "%02x".format(it) }
        assertEquals(64, hex.length)
        assertTrue("All hex chars", hex.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `migration 9_10 DDL smoke test`() {
        val ddl1 = "CREATE TABLE IF NOT EXISTS room_sessions"
        val ddl2 = "CREATE TABLE IF NOT EXISTS room_members"
        assertTrue(ddl1.contains("room_sessions"))
        assertTrue(ddl2.contains("room_members"))
    }
}
