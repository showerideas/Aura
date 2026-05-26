package com.showerideas.aura.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.utils.BackupUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Phase 6.10 — ViewModel backing [BackupFragment].
 *
 * Runs export/restore on [Dispatchers.IO], posts status messages via [statusMessage].
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun clearStatus() { _statusMessage.value = null }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    fun exportContacts(destUri: Uri, passphrase: CharArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contacts = contactRepository.allContacts.first()
                if (contacts.isEmpty()) {
                    _statusMessage.value = "No contacts to export."
                    passphrase.fill('\u0000')
                    return@launch
                }
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    BackupUtils.export(contacts, passphrase, out)
                }
                _statusMessage.value = "Backup saved — ${contacts.size} contact(s) exported."
                Timber.i("BackupUtils: exported ${contacts.size} contacts")
            } catch (e: Exception) {
                Timber.e(e, "BackupUtils: export failed")
                _statusMessage.value = "Export failed: ${e.message}"
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    // -------------------------------------------------------------------------
    // Restore
    // -------------------------------------------------------------------------

    fun restoreContacts(srcUri: Uri, passphrase: CharArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contacts = context.contentResolver.openInputStream(srcUri)?.use { input ->
                    BackupUtils.restore(passphrase, input)
                } ?: run {
                    _statusMessage.value = "Could not read backup file."
                    passphrase.fill('\u0000')
                    return@launch
                }
                // Upsert: contacts with matching IDs are updated, new ones inserted.
                contactRepository.upsertAll(contacts)
                _statusMessage.value = "Restored ${contacts.size} contact(s) successfully."
                Timber.i("BackupUtils: restored ${contacts.size} contacts")
            } catch (e: BackupUtils.BackupException) {
                Timber.w(e, "BackupUtils: restore failed (bad passphrase or corrupted file)")
                _statusMessage.value = "Restore failed — check your passphrase and try again."
            } catch (e: Exception) {
                Timber.e(e, "BackupUtils: restore failed unexpectedly")
                _statusMessage.value = "Restore failed: ${e.message}"
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }
}
