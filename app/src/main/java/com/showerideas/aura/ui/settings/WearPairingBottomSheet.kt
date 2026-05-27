package com.showerideas.aura.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.showerideas.aura.R
import com.showerideas.aura.databinding.BottomSheetWearPairingBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings → "Wear OS" companion pairing bottom sheet (phone-side).
 *
 * Shows:
 *   • Connected Wear OS watch nodes (display names from the Wearable Data Layer)
 *   • Pairing status (connected / no watch found)
 *   • A "How to pair" instructions row for first-time users
 *   • A "Refresh" button to re-scan for connected nodes
 *
 * The sheet is shown from [SettingsFragment] when the user taps the "Wear OS"
 * row. If the GMS Wearable API is unavailable (FOSS build / no Play Services),
 * a graceful "Wear OS not available" message is shown.
 */
@AndroidEntryPoint
class WearPairingBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "wear_pairing_sheet"
    }

    private var _binding: BottomSheetWearPairingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WearPairingViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetWearPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnWearRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnWearDone.setOnClickListener { dismissAllowingStateLoss() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }

        viewModel.refresh()
    }

    private fun renderState(state: WearPairingViewModel.UiState) {
        when (state) {
            is WearPairingViewModel.UiState.Loading -> {
                binding.tvWearStatus.text = getString(R.string.wear_pairing_scanning)
                binding.rvWatchList.visibility = View.GONE
                binding.tvWearHowTo.visibility = View.GONE
            }
            is WearPairingViewModel.UiState.NoWearableApi -> {
                binding.tvWearStatus.text = getString(R.string.wear_pairing_not_available)
                binding.rvWatchList.visibility = View.GONE
                binding.tvWearHowTo.visibility = View.VISIBLE
            }
            is WearPairingViewModel.UiState.NoNodesFound -> {
                binding.tvWearStatus.text = getString(R.string.wear_pairing_no_watches)
                binding.rvWatchList.visibility = View.GONE
                binding.tvWearHowTo.visibility = View.VISIBLE
            }
            is WearPairingViewModel.UiState.NodesFound -> {
                val names = state.nodeNames.joinToString("\n") { "• $it" }
                binding.tvWearStatus.text = resources.getQuantityString(
                    R.plurals.wear_pairing_connected,
                    state.nodeNames.size,
                    state.nodeNames.size,
                    names
                )
                binding.rvWatchList.visibility = View.GONE
                binding.tvWearHowTo.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
