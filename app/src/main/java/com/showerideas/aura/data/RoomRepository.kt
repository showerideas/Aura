package com.showerideas.aura.data

import com.showerideas.aura.data.local.RoomSessionDao
import com.showerideas.aura.model.RoomMember
import com.showerideas.aura.model.RoomSession
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for multi-party exchange Room sessions.
 *
 * Wraps [RoomSessionDao], enforces 10-minute TTL, and exposes [StateFlow]-backed
 * queries for the UI layer.
 */
@Singleton
class RoomRepository @Inject constructor(
    private val dao: RoomSessionDao
) {
    companion object {
        const val ROOM_TTL_MS = 10 * 60 * 1000L  // 10 minutes
        const val PIN_LENGTH  = 6
    }

    val activeRoom: Flow<RoomSession?> = dao.observeActiveRoom()

    suspend fun createRoom(): RoomSession {
        val rng          = SecureRandom()
        val roomIdBytes  = ByteArray(32).also { rng.nextBytes(it) }
        val roomKeyBytes = ByteArray(32).also { rng.nextBytes(it) }
        val roomId       = roomIdBytes.toHexString()
        val roomKey      = android.util.Base64.encodeToString(roomKeyBytes, android.util.Base64.NO_WRAP)
        val pin          = (rng.nextInt(900000) + 100000).toString()
        val now          = System.currentTimeMillis()
        val room = RoomSession(
            roomId    = roomId,
            roomKey   = roomKey,
            pin       = pin,
            createdAt = now,
            expiresAt = now + ROOM_TTL_MS,
            isHost    = true
        )
        dao.createRoom(room)
        return room
    }

    suspend fun joinRoom(roomId: String, roomKey: String, pin: String): RoomSession {
        val now  = System.currentTimeMillis()
        val room = RoomSession(
            roomId    = roomId,
            roomKey   = roomKey,
            pin       = pin,
            createdAt = now,
            expiresAt = now + ROOM_TTL_MS,
            isHost    = false
        )
        dao.createRoom(room)
        return room
    }

    fun observeRoom(roomId: String): Flow<RoomSession?>    = dao.observeRoom(roomId)
    fun observeMembers(roomId: String): Flow<List<RoomMember>> = dao.observeMembers(roomId)
    fun observeMemberCount(roomId: String): Flow<Int>      = dao.observeMemberCount(roomId)

    suspend fun addMember(member: RoomMember)               = dao.addMember(member)
    suspend fun markCardReceived(roomId: String, memberId: String) =
        dao.markCardReceived(roomId, memberId)
    suspend fun closeRoom(roomId: String)                   = dao.closeRoom(roomId)
    suspend fun purgeExpiredRooms()                         = dao.deleteExpiredRooms(System.currentTimeMillis())

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
