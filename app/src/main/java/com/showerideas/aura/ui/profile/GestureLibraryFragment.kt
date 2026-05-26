package com.showerideas.aura.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentGestureLibraryBinding
import com.showerideas.aura.databinding.ItemGestureProfileBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Phase 9.3 — Gesture Library screen.
 *
 * Displays up to [GestureLibraryViewModel.MAX_GESTURE_PROFILES] named gesture slots.
 * For each slot the user can:
 *   - Enroll (navigate to ProfileFragment in gesture-record mode for that slot).
 *   - Test live similarity (launches camera in test mode, shows match/fail toast).
 *   - Rename (inline text dialog).
 *   - Delete (confirmation dialog → ViewModel delete).
 *
 * Navigation: Settings → (future) Gesture library row, or Profile → "Manage gestures".
 */
@AndroidEntryPoint
class GestureLibraryFragment : Fragment() {

    private var _binding: FragmentGestureLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GestureLibraryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGestureLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarGestureLibrary.setNavigationOnClickListener { findNavController().navigateUp() }

        val adapter = GestureProfileAdapter(
            onEnroll = { slot -> navigateToEnroll(slot) },
            onTest   = { slot -> testGesture(slot) },
            onRename = { slot, current -> showRenameDialog(slot, current) },
            onDelete = { slot, name -> showDeleteConfirm(slot, name) }
        )
        binding.rvGestureLibrary.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGestureLibrary.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.profiles.collect { profiles ->
                    adapter.submitList(profiles)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /**
     * Navigate to ProfileFragment with the slot index in args so it records
     * into the specified gesture slot instead of the default (slot 0).
     *
     * TODO: pass slot arg once ProfileFragment supports multi-slot enrollment.
     */
    private fun navigateToEnroll(slot: Int) {
        // For now, navigate back to Profile to let user record in the primary slot.
        // Multi-slot enrollment wiring is tracked as a follow-up in Phase 9.3.
        Toast.makeText(requireContext(),
            getString(R.string.gesture_library_enroll_hint, slot + 1),
            Toast.LENGTH_SHORT).show()
        findNavController().navigateUp()
    }

    private fun testGesture(slot: Int) {
        // TODO Phase 9.3 follow-up: launch camera in test-match mode against slot `slot`.
        Toast.makeText(requireContext(), R.string.gesture_library_test_coming_soon, Toast.LENGTH_SHORT).show()
    }

    private fun showRenameDialog(slot: Int, currentName: String) {
        val input = EditText(requireContext()).apply {
            setText(currentName)
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.gesture_library_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim().takeIf { it.isNotBlank() } ?: currentName
                viewModel.renameProfile(slot, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirm(slot: Int, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.gesture_library_delete_title)
            .setMessage(getString(R.string.gesture_library_delete_message, name))
            .setPositiveButton(R.string.gesture_library_delete) { _, _ ->
                viewModel.deleteProfile(slot)
                Toast.makeText(requireContext(), R.string.gesture_library_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // -------------------------------------------------------------------------
    // RecyclerView adapter
    // -------------------------------------------------------------------------

    private inner class GestureProfileAdapter(
        private val onEnroll: (Int) -> Unit,
        private val onTest:   (Int) -> Unit,
        private val onRename: (Int, String) -> Unit,
        private val onDelete: (Int, String) -> Unit
    ) : RecyclerView.Adapter<GestureProfileAdapter.VH>() {

        private var items: List<GestureLibraryViewModel.GestureProfile> = emptyList()

        fun submitList(list: List<GestureLibraryViewModel.GestureProfile>) {
            items = list; notifyDataSetChanged()
        }

        inner class VH(private val binding: ItemGestureProfileBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(profile: GestureLibraryViewModel.GestureProfile) {
                binding.tvProfileName.text = profile.name
                if (profile.hasData) {
                    binding.tvProfileEnrolledAt.text = getString(
                        R.string.gesture_library_enrolled_at,
                        DateFormat.getDateTimeInstance().format(Date(profile.enrolledAt))
                    )
                    binding.tvProfileEnrolledAt.visibility = View.VISIBLE
                    binding.chipEnrolled.visibility  = View.VISIBLE
                    binding.chipEmpty.visibility     = View.GONE
                    binding.llEnrolledActions.visibility = View.VISIBLE
                    binding.btnEnrollGesture.visibility  = View.GONE

                    binding.btnTestGesture.setOnClickListener { onTest(profile.slot) }
                    binding.btnRenameGesture.setOnClickListener { onRename(profile.slot, profile.name) }
                    binding.btnDeleteGesture.setOnClickListener { onDelete(profile.slot, profile.name) }
                } else {
                    binding.tvProfileEnrolledAt.visibility = View.GONE
                    binding.chipEnrolled.visibility  = View.GONE
                    binding.chipEmpty.visibility     = View.VISIBLE
                    binding.llEnrolledActions.visibility = View.GONE
                    binding.btnEnrollGesture.visibility  = View.VISIBLE
                    binding.btnEnrollGesture.setOnClickListener { onEnroll(profile.slot) }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemGestureProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size
    }
}
