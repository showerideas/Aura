package com.showerideas.aura.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.showerideas.aura.R
import com.showerideas.aura.databinding.BottomSheetProfileSwitcherBinding
import com.showerideas.aura.model.ProfileType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Bottom sheet dialog that lists all profiles and allows the user to:
 *  - Switch the active profile by tapping a row.
 *  - Add a new Personal or Work profile.
 *  - Delete a non-active profile (with confirmation).
 *
 * Wired to [HomeViewModel] for all data operations so the Home screen chip
 * updates immediately without needing to dismiss and reopen.
 */
@AndroidEntryPoint
class ProfileSwitcherBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetProfileSwitcherBinding? = null
    private val binding get() = _binding!!

    // Shares the HomeViewModel with the parent fragment via activityViewModels.
    private val viewModel: HomeViewModel by viewModels()

    private val adapter = ProfileSwitcherAdapter(
        onSetActive = { profile ->
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.setActiveProfile(profile.id)
                dismiss()
            }
        },
        onDelete = { profile ->
            confirmDelete(profile.id,
                profile.displayName.ifBlank { profile.profileType.displayName() })
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetProfileSwitcherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProfiles.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allProfiles.collect { profiles ->
                    adapter.submitList(profiles)
                }
            }
        }

        binding.btnAddPersonal.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.createProfile(ProfileType.PERSONAL)
                Toast.makeText(requireContext(), R.string.profile_add, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddWork.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.createProfile(ProfileType.WORK)
                Toast.makeText(requireContext(), R.string.profile_add_work, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(id: String, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_confirm_title)
            .setMessage(R.string.profile_delete_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profile_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.deleteProfile(id)
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ProfileSwitcherBottomSheet"
    }
}
