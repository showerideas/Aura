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
import com.google.android.material.textfield.TextInputLayout
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentBackupBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Phase 6.10 — Encrypted backup / restore screen.
 *
 * Allows the user to:
 *   - Export all contacts to an AES-256-GCM encrypted file in Downloads.
 *   - Restore contacts from a previously exported backup file.
 *
 * Both operations require a passphrase. The passphrase never leaves the device
 * and is zeroed from memory as soon as the key derivation is complete.
 *
 * Navigation: Settings → Data section → "Backup & restore".
 */
@AndroidEntryPoint
class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BackupViewModel by viewModels()

    // SAF file pickers
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
            exportFilePicker.launch("aura_backup.aurbak")
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
    }

    // -------------------------------------------------------------------------
    // Passphrase dialogs
    // -------------------------------------------------------------------------

    private fun promptPassphraseForExport(destUri: Uri) {
        showPassphraseDialog(
            titleRes   = R.string.backup_passphrase_export_title,
            messageRes = R.string.backup_passphrase_export_message,
            confirmRes = R.string.backup_action_export
        ) { passphrase ->
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.exportContacts(destUri, passphrase)
            }
        }
    }

    private fun promptPassphraseForRestore(srcUri: Uri) {
        showPassphraseDialog(
            titleRes   = R.string.backup_passphrase_restore_title,
            messageRes = R.string.backup_passphrase_restore_message,
            confirmRes = R.string.backup_action_restore
        ) { passphrase ->
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.restoreContacts(srcUri, passphrase)
            }
        }
    }

    private fun showPassphraseDialog(
        titleRes: Int, messageRes: Int, confirmRes: Int,
        onConfirm: (CharArray) -> Unit
    ) {
        val inputLayout = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_passphrase_input, null)
        val textInput = inputLayout.findViewById<TextInputEditText>(R.id.et_passphrase)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(inputLayout)
            .setPositiveButton(confirmRes) { _, _ ->
                val passphrase = textInput.text.toString().toCharArray()
                // Zero the EditText content immediately
                textInput.text?.clear()
                if (passphrase.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.backup_passphrase_empty, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(passphrase)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
