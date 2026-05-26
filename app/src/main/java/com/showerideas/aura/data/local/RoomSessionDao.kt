package com.showerideas.aura.data.local

import androidx.room.*
import com.showerideas.aura.model.RoomMember
import com.showerideas.aura.model.RoomSession
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createRoom(room: RoomSession)

    @Query("SELECT * FROM room_sessions WHERE room_id = :roomId")
    fun observeRoom(roomId: String): Flow<RoomSession?>

    @Query("SELECT * FROM room_sessions WHERE state = 'ACTIVE' ORDER BY created_at DESC LIMIT 1")
    fun observeActiveRoom(): Flow<RoomSession?>

    @Query("UPDATE room_sessions SET state = 'CLOSED' WHERE room_id = :roomId")
    suspend fun closeRoom(roomId: String)

    @Query("DELETE FROM room_sessions WHERE expires_at < :nowMs")
    suspend fun deleteExpiredRooms(nowMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMember(member: RoomMember)

    @Query("SELECT * FROM room_members WHERE room_id = :roomId")
    fun observeMembers(roomId: String): Flow<List<RoomMember>>

    @Query("UPDATE room_members SET card_received = 1 WHERE room_id = :roomId AND member_id = :memberId")
    suspend fun markCardReceived(roomId: String, memberId: String)

    @Query("SELECT COUNT(*) FROM room_members WHERE room_id = :roomId")
    fun observeMemberCount(roomId: String): Flow<Int>
}
