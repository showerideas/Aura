package com.showerideas.aura.ui.exchange

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.showerideas.aura.R
import com.showerideas.aura.model.SharePreset
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Phase 9.1 — Quick-select bottom sheet shown before an exchange starts.
 * Displays the user's share presets as chips; tapping one applies it and
 * dismisses the sheet.
 *
 * @param onPresetSelected called with the selected [SharePreset] when the user taps a chip.
 */
@AndroidEntryPoint
class SharePresetBottomSheet(
    private val onPresetSelected: (SharePreset) -> Unit
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SharePresetBottomSheet"
    }

    private val viewModel: ExchangeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_share_preset, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_presets)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sharePresets.collect { presets ->
                chipGroup.removeAllViews()
                presets.forEach { preset ->
                    val chip = Chip(requireContext()).apply {
                        text = preset.name
                        isCheckable = false
                        setOnClickListener {
                            viewModel.selectPreset(preset)
                            onPresetSelected(preset)
                            dismissAllowingStateLoss()
                        }
                    }
                    chipGroup.addView(chip)
                }
            }
        }
    }
}
