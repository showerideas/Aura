package com.showerideas.aura.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A participant in an AURA exchange room.
 */
@Entity(
    tableName = "room_members",
    primaryKeys = ["room_id", "member_id"],
    foreignKeys = [ForeignKey(
        entity = RoomSession::class,
        parentColumns = ["room_id"],
        childColumns = ["room_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("room_id")]
)
data class RoomMember(
    @ColumnInfo(name = "room_id")
    val roomId: String,

    /** Stable identity key hash of the member. */
    @ColumnInfo(name = "member_id")
    val memberId: String,

    /** Display name from their vCard (unverified). */
    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** Epoch ms when this member joined. */
    @ColumnInfo(name = "joined_at")
    val joinedAt: Long,

    /** Whether this member's card has been received and saved. */
    @ColumnInfo(name = "card_received")
    val cardReceived: Boolean = false
)

