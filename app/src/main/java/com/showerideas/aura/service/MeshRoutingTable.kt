package com.showerideas.aura.service

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-hop Wi-Fi Direct mesh routing table with TTL enforcement.
 *
 * Architecture
 * AURA's mesh layer extends the point-to-point [WifiDirectTransport] into a
 * store-and-forward multi-hop network. Each device maintains a routing table
 * of known mesh peers, tracking the best next-hop and current TTL.
 *
 * ```
 *  Device A ──── Device B ──── Device C ──── Device D
 *                              ^
 *                              └── Device A sends to D via B→C→D (2 hops)
 * ```
 *
 * Packet format (prepended to every routed payload)
 * ```
 * ┌────────────────────────────────────────────────────────────┐
 * │ originId (64 chars hex)  │ destId (64 chars hex)           │
 * │ msgId   (36 chars UUID)  │ ttl (1 byte, 0–15)              │
 * │ hopCount (1 byte)        │ payload ...                     │
 * └────────────────────────────────────────────────────────────┘
 * ```
 *
 * TTL semantics
 * - [DEFAULT_TTL] = 7 hops — sufficient for most encounter graphs
 * - Each relay decrements TTL by 1; packets with TTL == 0 are dropped
 * - [MAX_TTL] = 15 caps values from untrusted sources
 *
 * Loop prevention
 * A rolling [SEEN_CACHE_SIZE]-entry cache of recently-forwarded message IDs
 * prevents forwarding the same packet twice. Entries expire after
 * [SEEN_CACHE_TTL_MS] (60 s).
 *
 * Local hotspot underlay
 * When the device is acting as a Wi-Fi Direct Group Owner it can also open a
 * standard Wi-Fi hotspot (via [requestLocalHotspot]) so devices that don't
 * support Wi-Fi Direct can still connect as regular Wi-Fi clients. The
 * [HotspotConfig] is shared out-of-band (e.g. via BLE advertisement or QR
 * code) so legacy devices can join the mesh.
 */
class MeshRoutingTable {

    companion object {
        /** Default TTL for new packets originating on this device. */
        const val DEFAULT_TTL: Int = 7

        /** Maximum TTL accepted from peers (caps crafted high-TTL packets). */
        const val MAX_TTL: Int = 15

        /** Maximum number of entries in the routing table. */
        const val MAX_ROUTES: Int = 64

        /** Maximum number of message IDs in the seen-cache. */
        const val SEEN_CACHE_SIZE: Int = 512

        /** How long seen-cache entries are valid (ms). */
        const val SEEN_CACHE_TTL_MS: Long = 60_000L

        /** Maximum number of hops a packet will actually traverse. */
        const val MAX_HOP_COUNT: Int = MAX_TTL
    }

    // Data types

    /**
     * A single routing-table entry for a known mesh peer.
     *
     * @param destId        64-hex identity hash of the destination node.
     * @param nextHopId     64-hex identity hash of the next-hop peer.
     * @param hopCount      Number of hops to [destId] via [nextHopId].
     * @param lastSeenMs    Epoch ms when we last received a frame from this route.
     * @param rssi          Last observed RSSI to [nextHopId] (dBm), or [Int.MIN_VALUE] if unknown.
     */
    data class RouteEntry(
        val destId   : String,
        val nextHopId: String,
        val hopCount : Int,
        val lastSeenMs: Long,
        val rssi     : Int = Int.MIN_VALUE
    )

    /**
     * A mesh packet header — extracted from wire bytes before routing.
     *
     * @param originId  Original sender.
     * @param destId    Intended final recipient ("*" = broadcast).
     * @param msgId     UUID string for dedup.
     * @param ttl       Remaining TTL (0 = drop immediately).
     * @param hopCount  Number of hops this packet has already traversed.
     * @param payload   Raw application bytes after the header.
     */
    data class MeshPacket(
        val originId : String,
        val destId   : String,
        val msgId    : String,
        val ttl      : Int,
        val hopCount : Int,
        val payload  : ByteArray
    ) {
        override fun equals(other: Any?) = other is MeshPacket && msgId == other.msgId
        override fun hashCode()         = msgId.hashCode()
    }

    /**
     * Wi-Fi hotspot configuration shared with legacy (non-P2P) devices.
     */
    data class HotspotConfig(
        val ssid      : String,
        val passphrase: String,
        val bssid     : String,
        val channel   : Int = 6
    )

    // State

    /** Primary routing table: destId → best route entry. */
    private val routes = ConcurrentHashMap<String, RouteEntry>(MAX_ROUTES)

    /** Seen-packet cache: msgId → epoch-ms when first seen. */
    private val seenCache = ConcurrentHashMap<String, Long>(SEEN_CACHE_SIZE)

    /** Current local hotspot config (null if no hotspot active). */
    @Volatile
    private var hotspotConfig: HotspotConfig? = null

    // Route management

    /**
     * Add or update a route. If a route to [destId] already exists, only
     * replace it if the new route has fewer or equal hops and a fresher timestamp.
     */
    fun upsertRoute(
        destId   : String,
        nextHopId: String,
        hopCount : Int,
        rssi     : Int = Int.MIN_VALUE
    ) {
        val existing = routes[destId]
        val newEntry = RouteEntry(
            destId    = destId,
            nextHopId = nextHopId,
            hopCount  = hopCount.coerceAtMost(MAX_HOP_COUNT),
            lastSeenMs = System.currentTimeMillis(),
            rssi      = rssi
        )
        if (existing == null || newEntry.hopCount <= existing.hopCount) {
            routes[destId] = newEntry
            Timber.d("MeshRoutingTable: upsert route to %s via %s (%d hops)",
                destId.take(8), nextHopId.take(8), hopCount)
        }
        evictStaleRoutes()
    }

    /** Remove a specific route. */
    fun removeRoute(destId: String) {
        routes.remove(destId)
        Timber.d("MeshRoutingTable: removed route to %s", destId.take(8))
    }

    /** Best known route to [destId], or null if unknown. */
    fun routeTo(destId: String): RouteEntry? = routes[destId]

    /** All current routes (snapshot). */
    fun allRoutes(): List<RouteEntry> = routes.values.toList()

    /** Number of active routes. */
    fun size(): Int = routes.size

    // Packet forwarding

    /**
     * Decide whether and where to forward [packet].
     *
     * @return [ForwardDecision] indicating action to take.
     */
    fun forwardDecision(localId: String, packet: MeshPacket): ForwardDecision {
        // 0. Deduplicate
        if (hasSeen(packet.msgId)) {
            return ForwardDecision.Drop("duplicate msgId ${packet.msgId.take(8)}")
        }

        // 1. TTL enforcement — clamp untrusted values, then decrement
        val clampedTtl = packet.ttl.coerceIn(0, MAX_TTL)
        if (clampedTtl == 0) {
            return ForwardDecision.Drop("TTL exhausted")
        }
        val decrementedTtl = clampedTtl - 1

        // 2. Mark seen before forwarding to prevent loops on concurrent paths
        markSeen(packet.msgId)

        val forwarded = packet.copy(ttl = decrementedTtl, hopCount = packet.hopCount + 1)

        // 3. Broadcast
        if (packet.destId == "*") {
            return ForwardDecision.Broadcast(forwarded)
        }

        // 4. Deliver locally
        if (packet.destId == localId) {
            return ForwardDecision.DeliverLocal(forwarded)
        }

        // 5. Unicast via routing table
        val route = routes[packet.destId]
        return if (route != null) {
            ForwardDecision.Unicast(forwarded, nextHopId = route.nextHopId)
        } else {
            // No known route — flood to all neighbours (reactive routing)
            ForwardDecision.Flood(forwarded)
        }
    }

    sealed class ForwardDecision {
        data class DeliverLocal(val packet: MeshPacket) : ForwardDecision()
        data class Unicast(val packet: MeshPacket, val nextHopId: String) : ForwardDecision()
        data class Broadcast(val packet: MeshPacket) : ForwardDecision()
        data class Flood(val packet: MeshPacket) : ForwardDecision()
        data class Drop(val reason: String) : ForwardDecision()
    }

    // Packet serialisation

    /**
     * Serialise [packet] to bytes for wire transmission.
     *
     * Format:
     * ```
     * [64-char originId] [64-char destId] [36-char msgId] [1-byte ttl]
     * [1-byte hopCount] [4-byte payload length LE] [payload bytes]
     * ```
     * Total header: 64+64+36+1+1+4 = 170 bytes.
     */
    fun serialise(packet: MeshPacket): ByteArray {
        val payloadLen = packet.payload.size
        val buf = ByteArray(170 + payloadLen)
        var pos = 0

        fun writeFixedString(s: String, len: Int) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            bytes.copyInto(buf, pos, 0, minOf(bytes.size, len))
            pos += len
        }
        fun writeByte(b: Int) { buf[pos++] = b.and(0xFF).toByte() }
        fun writeInt32LE(v: Int) {
            buf[pos++] = (v and 0xFF).toByte()
            buf[pos++] = (v shr 8  and 0xFF).toByte()
            buf[pos++] = (v shr 16 and 0xFF).toByte()
            buf[pos++] = (v shr 24 and 0xFF).toByte()
        }

        writeFixedString(packet.originId.padEnd(64).take(64), 64)
        writeFixedString(packet.destId.padEnd(64).take(64),   64)
        writeFixedString(packet.msgId.padEnd(36).take(36),    36)
        writeByte(packet.ttl.coerceIn(0, MAX_TTL))
        writeByte(packet.hopCount.coerceIn(0, MAX_HOP_COUNT))
        writeInt32LE(payloadLen)
        packet.payload.copyInto(buf, pos)
        return buf
    }

    /**
     * Deserialise bytes produced by [serialise]. Returns null on parse error.
     */
    fun deserialise(bytes: ByteArray): MeshPacket? {
        if (bytes.size < 170) return null
        return try {
            val originId  = String(bytes, 0,   64, Charsets.UTF_8).trimEnd()
            val destId    = String(bytes, 64,  64, Charsets.UTF_8).trimEnd()
            val msgId     = String(bytes, 128, 36, Charsets.UTF_8).trimEnd()
            val ttl       = bytes[164].toInt() and 0xFF
            val hopCount  = bytes[165].toInt() and 0xFF
            val payLen    = (bytes[166].toInt() and 0xFF) or
                            ((bytes[167].toInt() and 0xFF) shl 8) or
                            ((bytes[168].toInt() and 0xFF) shl 16) or
                            ((bytes[169].toInt() and 0xFF) shl 24)
            if (bytes.size < 170 + payLen) return null
            val payload   = bytes.copyOfRange(170, 170 + payLen)
            MeshPacket(originId, destId, msgId, ttl.coerceIn(0, MAX_TTL), hopCount, payload)
        } catch (e: Exception) {
            Timber.w(e, "MeshRoutingTable: deserialise failed")
            null
        }
    }

    // Local hotspot underlay

    /**
     * Record the active local hotspot configuration so the mesh layer can
     * advertise it to legacy (non-Wi-Fi-Direct) devices via BLE or QR.
     */
    fun setHotspotConfig(config: HotspotConfig?) {
        hotspotConfig = config
        if (config != null) {
            Timber.i("MeshRoutingTable: local hotspot underlay active SSID=%s", config.ssid)
        } else {
            Timber.i("MeshRoutingTable: local hotspot underlay cleared")
        }
    }

    /** Current local hotspot config, or null if no hotspot is active. */
    fun getHotspotConfig(): HotspotConfig? = hotspotConfig

    // Seen-packet cache

    private fun hasSeen(msgId: String): Boolean {
        pruneSeenCache()
        return seenCache.containsKey(msgId)
    }

    private fun markSeen(msgId: String) {
        if (seenCache.size >= SEEN_CACHE_SIZE) pruneSeenCache()
        seenCache[msgId] = System.currentTimeMillis()
    }

    private fun pruneSeenCache() {
        val cutoff = System.currentTimeMillis() - SEEN_CACHE_TTL_MS
        seenCache.entries.removeIf { it.value < cutoff }
    }

    // Route eviction

    /**
     * Evict the oldest route when the table is at capacity.
     * A route is stale if it hasn't been refreshed in >5 minutes.
     */
    private fun evictStaleRoutes() {
        if (routes.size < MAX_ROUTES) return
        val staleCutoff = System.currentTimeMillis() - 5 * 60_000L
        val stale = routes.entries.filter { it.value.lastSeenMs < staleCutoff }
        stale.forEach { routes.remove(it.key) }
        if (stale.isEmpty() && routes.size >= MAX_ROUTES) {
            // No stale entries — evict the oldest
            routes.entries.minByOrNull { it.value.lastSeenMs }?.key?.let { routes.remove(it) }
        }
    }
}
