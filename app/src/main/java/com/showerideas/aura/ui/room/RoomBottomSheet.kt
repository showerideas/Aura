package com.showerideas.aura.ui.room

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Task 9 — Bottom sheet for creating or displaying an active exchange room.
 *
 * Host path: shows room PIN + live participant count via [RoomViewModel.roomState].
 * Join path: caller passes PIN to [RoomViewModel.joinRoom] before showing this sheet.
 */
@AndroidEntryPoint
class RoomBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: RoomViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(android.R.layout.simple_list_item_2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val text1 = view.findViewById<TextView>(android.R.id.text1)
        val text2 = view.findViewById<TextView>(android.R.id.text2)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.roomState.collect { state ->
                when (state) {
                    is RoomUiState.Hosting -> {
                        text1?.text = "Room active — PIN: ${state.room.pin}"
                        text2?.text = "Room ID: ${state.room.roomId.take(16)}…"
                    }
                    is RoomUiState.Joined -> {
                        text1?.text = "Joined room — PIN: ${state.room.pin}"
                        text2?.text = "Waiting for cards…"
                    }
                    is RoomUiState.Creating -> {
                        text1?.text = "Creating room…"
                        text2?.text = null
                    }
                    is RoomUiState.Error -> {
                        text1?.text = "Error: ${state.message}"
                        text2?.text = null
                    }
                    else -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.members.collect { members ->
                if (members.isNotEmpty()) {
                    text2?.text = "${members.size} participant(s) joined"
                }
            }
        }
    }

    companion object {
        const val TAG = "RoomBottomSheet"
        fun newInstance() = RoomBottomSheet()
    }
}
