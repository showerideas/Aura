package com.showerideas.aura.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T21 — Coverage milestone 70%: unit tests for [MeshRoutingTable].
 *
 * Tests routing decisions, TTL enforcement, deduplication, serialisation,
 * and hotspot-underlay config.
 */
class MeshRoutingTableTest {

    private lateinit var table: MeshRoutingTable

    private val LOCAL_ID = "a".repeat(64)
    private val PEER_A   = "b".repeat(64)
    private val PEER_B   = "c".repeat(64)
    private val PEER_C   = "d".repeat(64)

    @Before
    fun setUp() { table = MeshRoutingTable() }

    private fun packet(
        origin: String = LOCAL_ID,
        dest  : String = PEER_C,
        msgId : String = java.util.UUID.randomUUID().toString(),
        ttl   : Int    = MeshRoutingTable.DEFAULT_TTL,
        hop   : Int    = 0,
        payload: ByteArray = byteArrayOf(1, 2, 3)
    ) = MeshRoutingTable.MeshPacket(origin, dest, msgId, ttl, hop, payload)

    // -------------------------------------------------------------------------
    // Route management
    // -------------------------------------------------------------------------

    @Test
    fun `upsertRoute adds entry`() {
        table.upsertRoute(PEER_A, PEER_B, hopCount = 1)
        assertEquals(1, table.size())
        val route = table.routeTo(PEER_A)
        assertNotNull(route)
        assertEquals(PEER_B, route!!.nextHopId)
        assertEquals(1, route.hopCount)
    }

    @Test
    fun `upsertRoute replaces on better hop count`() {
        table.upsertRoute(PEER_A, PEER_B, hopCount = 3)
        table.upsertRoute(PEER_A, PEER_C, hopCount = 1)
        assertEquals(PEER_C, table.routeTo(PEER_A)?.nextHopId)
        assertEquals(1, table.routeTo(PEER_A)?.hopCount)
    }

    @Test
    fun `upsertRoute keeps existing on worse hop count`() {
        table.upsertRoute(PEER_A, PEER_C, hopCount = 1)
        table.upsertRoute(PEER_A, PEER_B, hopCount = 5)
        assertEquals(PEER_C, table.routeTo(PEER_A)?.nextHopId)
    }

    @Test
    fun `removeRoute removes entry`() {
        table.upsertRoute(PEER_A, PEER_B, hopCount = 1)
        table.removeRoute(PEER_A)
        assertNull(table.routeTo(PEER_A))
        assertEquals(0, table.size())
    }

    @Test
    fun `routeTo returns null for unknown dest`() {
        assertNull(table.routeTo("unknown_dest"))
    }

    // -------------------------------------------------------------------------
    // Forward decisions
    // -------------------------------------------------------------------------

    @Test
    fun `TTL 0 → Drop`() {
        val p = packet(ttl = 0)
        val dec = table.forwardDecision(LOCAL_ID, p)
        assertTrue("TTL=0 must Drop, got $dec", dec is MeshRoutingTable.ForwardDecision.Drop)
    }

    @Test
    fun `dest == localId → DeliverLocal`() {
        val p = packet(dest = LOCAL_ID, ttl = 5)
        val dec = table.forwardDecision(LOCAL_ID, p)
        assertTrue("Dest=local must DeliverLocal, got $dec",
            dec is MeshRoutingTable.ForwardDecision.DeliverLocal)
    }

    @Test
    fun `broadcast dest → Broadcast`() {
        val p = packet(dest = "*", ttl = 5)
        val dec = table.forwardDecision(LOCAL_ID, p)
        assertTrue("Broadcast must → Broadcast, got $dec",
            dec is MeshRoutingTable.ForwardDecision.Broadcast)
    }

    @Test
    fun `known route → Unicast via next hop`() {
        table.upsertRoute(PEER_C, PEER_B, hopCount = 2)
        val p = packet(dest = PEER_C, ttl = 5)
        val dec = table.forwardDecision(LOCAL_ID, p)
        assertTrue("Known route must Unicast, got $dec",
            dec is MeshRoutingTable.ForwardDecision.Unicast)
        assertEquals(PEER_B, (dec as MeshRoutingTable.ForwardDecision.Unicast).nextHopId)
    }

    @Test
    fun `unknown route → Flood`() {
        val p = packet(dest = PEER_C, ttl = 5)
        val dec = table.forwardDecision(LOCAL_ID, p)
        assertTrue("Unknown route must Flood, got $dec",
            dec is MeshRoutingTable.ForwardDecision.Flood)
    }

    @Test
    fun `duplicate msgId → Drop`() {
        val p = packet(ttl = 5)
        table.forwardDecision(LOCAL_ID, p)               // first time — processes
        val dec2 = table.forwardDecision(LOCAL_ID, p)    // second time — duplicate
        assertTrue("Duplicate must Drop, got $dec2",
            dec2 is MeshRoutingTable.ForwardDecision.Drop)
    }

    @Test
    fun `TTL is decremented on forwarded packets`() {
        val p = packet(dest = PEER_C, ttl = 5)
        val dec = table.forwardDecision(LOCAL_ID, p)
        assertTrue(dec is MeshRoutingTable.ForwardDecision.Flood)
        assertEquals(4, (dec as MeshRoutingTable.ForwardDecision.Flood).packet.ttl)
    }

    @Test
    fun `hop count is incremented on forwarded packets`() {
        val p = packet(dest = PEER_C, ttl = 5, hop = 2)
        val dec = table.forwardDecision(LOCAL_ID, p)
        assertTrue(dec is MeshRoutingTable.ForwardDecision.Flood)
        assertEquals(3, (dec as MeshRoutingTable.ForwardDecision.Flood).packet.hopCount)
    }

    @Test
    fun `TTL above MAX_TTL is clamped`() {
        val p = packet(ttl = 999)
        val dec = table.forwardDecision(LOCAL_ID, p)
        // Should not crash; TTL is clamped to MAX_TTL then decremented
        assertFalse("Should not be Drop for valid TTL after clamp",
            dec is MeshRoutingTable.ForwardDecision.Drop)
    }

    // -------------------------------------------------------------------------
    // Serialisation round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `serialise and deserialise round-trip preserves all fields`() {
        val p = packet(
            origin  = PEER_A,
            dest    = PEER_B,
            msgId   = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            ttl     = 6,
            hop     = 2,
            payload = "Hello AURA mesh".toByteArray()
        )
        val bytes = table.serialise(p)
        val restored = table.deserialise(bytes)
        assertNotNull(restored)
        assertEquals(PEER_A,   restored!!.originId.trimEnd())
        assertEquals(PEER_B,   restored.destId.trimEnd())
        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", restored.msgId.trimEnd())
        assertEquals(6,        restored.ttl)
        assertEquals(2,        restored.hopCount)
        assertTrue("Payload content must match",
            "Hello AURA mesh".toByteArray().contentEquals(restored.payload))
    }

    @Test
    fun `deserialise returns null for too-short input`() {
        assertNull(table.deserialise(ByteArray(10)))
    }

    @Test
    fun `deserialise returns null for empty bytes`() {
        assertNull(table.deserialise(ByteArray(0)))
    }

    // -------------------------------------------------------------------------
    // Hotspot config
    // -------------------------------------------------------------------------

    @Test
    fun `hotspot config initially null`() {
        assertNull(table.getHotspotConfig())
    }

    @Test
    fun `setHotspotConfig stores config`() {
        val cfg = MeshRoutingTable.HotspotConfig("AURA_MESH", "secret1234", "aa:bb:cc:dd:ee:ff")
        table.setHotspotConfig(cfg)
        assertEquals(cfg, table.getHotspotConfig())
    }

    @Test
    fun `setHotspotConfig null clears config`() {
        table.setHotspotConfig(MeshRoutingTable.HotspotConfig("SSID", "pass", "00:00:00:00:00:00"))
        table.setHotspotConfig(null)
        assertNull(table.getHotspotConfig())
    }
}
