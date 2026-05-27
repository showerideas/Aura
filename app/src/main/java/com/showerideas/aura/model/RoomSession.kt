package com.showerideas.aura.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An AURA multi-party exchange room session.
 *
 * A Room is a temporary cryptographic session in which N participants
 * simultaneously exchange contact cards. The host creates the room;
 * other participants join via QR code, PIN, BLE scan, or NFC tap.
 *
 * Lifecycle: ACTIVE → CLOSED (after 10 minutes or host closes manually).
 */
@Entity(tableName = "room_sessions")
data class RoomSession(
    /** 32-byte secure random room identifier (hex-encoded, 64 chars). */
    @PrimaryKey
    @ColumnInfo(name = "room_id")
    val roomId: String,

    /** AES-256 room key (base64-encoded, 32 bytes). */
    @ColumnInfo(name = "room_key")
    val roomKey: String,

    /** 6-digit PIN for key wrapping / user confirmation. */
    @ColumnInfo(name = "pin")
    val pin: String,

    /** Epoch ms when this room was created. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** Epoch ms when this room expires (createdAt + 10 minutes). */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,

    /** Current room lifecycle state. */
    @ColumnInfo(name = "state")
    val state: RoomState = RoomState.ACTIVE,

    /** True if this device is the host. */
    @ColumnInfo(name = "is_host")
    val isHost: Boolean = false
)

enum class RoomState { ACTIVE, CLOSED }

