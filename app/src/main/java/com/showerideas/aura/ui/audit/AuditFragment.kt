package com.showerideas.aura.ui.audit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentAuditBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Exchange audit log screen (Phase 6.6).
 *
 * Shows a timeline of all [ExchangeAuditEntry] rows from Room in reverse
 * chronological order. Toolbar actions:
 *  - Export log → CSV in Downloads.
 *  - Clear log → confirmation dialog → [AuditViewModel.clearAll].
 *
 * Navigation: Contacts overflow menu → "Exchange history"
 *             Settings → Security → "Exchange history"
 */
@AndroidEntryPoint
class AuditFragment : Fragment() {

    private var _binding: FragmentAuditBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuditViewModel by viewModels()
    private val adapter = AuditAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarAudit.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.rvAudit.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAudit.adapter = adapter

        // Menu provider (export + clear actions)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.audit_menu, menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean {
                return when (item.itemId) {
                    R.id.action_export_audit -> {
                        val file = viewModel.exportToCsv(requireContext())
                        if (file != null) {
                            Toast.makeText(requireContext(), R.string.audit_export_done, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), R.string.audit_empty, Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_clear_audit -> {
                        confirmClear()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        // Observe entries
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.entries.collect { entries ->
                    adapter.submitList(entries)
                    binding.tvAuditEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvAudit.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.audit_clear_confirm_title)
            .setMessage(R.string.audit_clear_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.audit_clear) { _, _ ->
                viewModel.clearAll()
                Toast.makeText(requireContext(), R.string.audit_clear_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
