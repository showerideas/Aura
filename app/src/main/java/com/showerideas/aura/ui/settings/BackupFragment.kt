package com.showerideas.aura.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentBackupBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encrypted backup / restore screen (polished).
 *
 * Improvements over baseline:
 * - Export filename includes the current date: `aura_backup_YYYYMMDD.aurbak`
 * - Passphrase confirmation field on the export dialog catches typos before
 *   the key derivation runs (a typo locks the user out of their own backup).
 * - Progress overlay driven by [BackupViewModel.isLoading] blocks both buttons
 *   during the ~1 s PBKDF2 operation.
 * - Contact count displayed on the screen so the user can see how many
 *   contacts will be included before starting the export.
 * - Pre-restore confirmation dialog: "This will update N contacts. Proceed?"
 */
@AndroidEntryPoint
class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BackupViewModel by viewModels()

    private val exportFilePicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) promptPassphraseForExport(uri)
    }

    private val importFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) promptPassphraseForRestore(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarBackup.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.btnExportBackup.setOnClickListener {
            exportFilePicker.launch(exportFileName())
        }

        binding.btnImportBackup.setOnClickListener {
            importFilePicker.launch(arrayOf("*/*"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statusMessage.collect { msg ->
                if (msg != null) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    viewModel.clearStatus()
                }
            }
        }

        // Progress overlay — disable buttons while crypto is running
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressBackup.visibility = if (loading) View.VISIBLE else View.GONE
                binding.btnExportBackup.isEnabled = !loading
                binding.btnImportBackup.isEnabled = !loading
            }
        }

        // Live contact count
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.contactCount.collect { count ->
                binding.tvContactCount.text = resources.getQuantityString(
                    R.plurals.backup_contact_count, count, count
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Passphrase dialogs
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Export dialog: two passphrase fields — entry + confirmation.
     * Confirms the passphrases match before launching the export operation.
     */
    private fun promptPassphraseForExport(destUri: Uri) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_passphrase_export, null)
        val etPassphrase = dialogView.findViewById<TextInputEditText>(R.id.et_passphrase)
        val etConfirm    = dialogView.findViewById<TextInputEditText>(R.id.et_passphrase_confirm)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_passphrase_export_title)
            .setMessage(R.string.backup_passphrase_export_message)
            .setView(dialogView)
            .setPositiveButton(R.string.backup_action_export) { _, _ ->
                val pass    = etPassphrase.text.toString()
                val confirm = etConfirm.text.toString()
                etPassphrase.text?.clear()
                etConfirm.text?.clear()

                when {
                    pass.isEmpty() ->
                        Toast.makeText(requireContext(), R.string.backup_passphrase_empty, Toast.LENGTH_SHORT).show()
                    pass != confirm ->
                        Toast.makeText(requireContext(), R.string.backup_passphrase_mismatch, Toast.LENGTH_SHORT).show()
                    else ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewModel.exportContacts(destUri, pass.toCharArray())
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Restore dialog: single passphrase field, followed by a confirmation dialog
     * that shows the contact count about to be merged.
     */
    private fun promptPassphraseForRestore(srcUri: Uri) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_passphrase_input, null)
        val etPassphrase = dialogView.findViewById<TextInputEditText>(R.id.et_passphrase)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_passphrase_restore_title)
            .setMessage(R.string.backup_passphrase_restore_message)
            .setView(dialogView)
            .setPositiveButton(R.string.backup_action_restore) { _, _ ->
                val pass = etPassphrase.text.toString().toCharArray()
                etPassphrase.text?.clear()
                if (pass.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.backup_passphrase_empty, Toast.LENGTH_SHORT).show()
                } else {
                    showRestoreConfirmation(srcUri, pass)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Confirmation dialog shown before restore: "This will update your contacts. Proceed?"
     * Gives the user one last chance to cancel before data is merged.
     */
    private fun showRestoreConfirmation(srcUri: Uri, passphrase: CharArray) {
        val count = viewModel.contactCount.value
        val msg   = resources.getQuantityString(R.plurals.backup_restore_confirm_message, count, count)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(msg)
            .setPositiveButton(R.string.backup_action_restore) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.restoreContacts(srcUri, passphrase)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                passphrase.fill('\u0000')  // zero passphrase if user cancels
            }
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Generate a date-stamped filename: `aura_backup_20260526.aurbak` */
    private fun exportFileName(): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "aura_backup_$date.aurbak"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

