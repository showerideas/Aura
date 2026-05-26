package com.showerideas.aura.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.RoomRepository
import com.showerideas.aura.model.RoomMember
import com.showerideas.aura.model.RoomSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Task 9 — ViewModel for the Room creation / join flow.
 */
@HiltViewModel
class RoomViewModel @Inject constructor(
    private val repository: RoomRepository
) : ViewModel() {

    private val _roomState = MutableStateFlow<RoomUiState>(RoomUiState.Idle)
    val roomState: StateFlow<RoomUiState> = _roomState.asStateFlow()

    private val _members = MutableStateFlow<List<RoomMember>>(emptyList())
    val members: StateFlow<List<RoomMember>> = _members.asStateFlow()

    val activeRoom: StateFlow<RoomSession?> = repository.activeRoom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun createRoom() {
        viewModelScope.launch {
            _roomState.value = RoomUiState.Creating
            try {
                val room = repository.createRoom()
                _roomState.value = RoomUiState.Hosting(room)
                observeMembers(room.roomId)
                scheduleAutoClose(room)
            } catch (e: Exception) {
                _roomState.value = RoomUiState.Error(e.message ?: "Failed to create room")
            }
        }
    }

    fun joinRoom(roomId: String, roomKey: String, pin: String) {
        viewModelScope.launch {
            _roomState.value = RoomUiState.Joining
            try {
                val room = repository.joinRoom(roomId, roomKey, pin)
                _roomState.value = RoomUiState.Joined(room)
                observeMembers(room.roomId)
            } catch (e: Exception) {
                _roomState.value = RoomUiState.Error(e.message ?: "Failed to join room")
            }
        }
    }

    fun closeRoom() {
        val roomId = when (val s = _roomState.value) {
            is RoomUiState.Hosting -> s.room.roomId
            is RoomUiState.Joined  -> s.room.roomId
            else -> return
        }
        viewModelScope.launch {
            repository.closeRoom(roomId)
            _roomState.value = RoomUiState.Idle
        }
    }

    private fun observeMembers(roomId: String) {
        viewModelScope.launch {
            repository.observeMembers(roomId).collect { _members.value = it }
        }
    }

    private fun scheduleAutoClose(room: RoomSession) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(RoomRepository.ROOM_TTL_MS)
            if (_roomState.value is RoomUiState.Hosting) {
                repository.closeRoom(room.roomId)
                _roomState.value = RoomUiState.Idle
            }
        }
    }
}

sealed interface RoomUiState {
    object Idle     : RoomUiState
    object Creating : RoomUiState
    object Joining  : RoomUiState
    data class Hosting(val room: RoomSession) : RoomUiState
    data class Joined (val room: RoomSession) : RoomUiState
    data class Error  (val message: String)   : RoomUiState
}
