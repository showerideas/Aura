package com.showerideas.aura.security

import org.junit.Assert.*
import org.junit.Test

class PinValidatorTest {

    private val PRIMARY = "abc123primaryhash=="
    private val BACKUP  = "def456backuphash=="

    @Test
    fun `primary pin match returns MATCH_PRIMARY`() {
        assertEquals(PinValidator.PinResult.MATCH_PRIMARY,
            PinValidator.validatePin(PRIMARY, PRIMARY, BACKUP))
    }

    @Test
    fun `backup pin match returns MATCH_BACKUP`() {
        assertEquals(PinValidator.PinResult.MATCH_BACKUP,
            PinValidator.validatePin(BACKUP, PRIMARY, BACKUP))
    }

    @Test
    fun `unknown hash returns MISMATCH`() {
        assertEquals(PinValidator.PinResult.MISMATCH,
            PinValidator.validatePin("unknown==", PRIMARY, BACKUP))
    }

    @Test
    fun `empty backup pin — unknown hash still mismatches`() {
        assertEquals(PinValidator.PinResult.MISMATCH,
            PinValidator.validatePin("unknown==", PRIMARY, ""))
    }
}
