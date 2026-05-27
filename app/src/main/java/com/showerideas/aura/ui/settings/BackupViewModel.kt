package com.showerideas.aura.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.R
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
 * ViewModel backing [BackupFragment] (polished).
 *
 * Improvements over the baseline:
 * - [isLoading] StateFlow drives a progress indicator in the UI so the user
 *   knows when the ~1 s PBKDF2 derivation is running.
 * - Status messages use string resources (via [Context.getString]) rather than
 *   hardcoded English literals, making them translatable.
 * - [contactCount] exposes the current contact count so the backup screen can
 *   show "N contacts" without querying the repo from the Fragment.
 * - Export / restore operations remain on [Dispatchers.IO]; [isLoading] is
 *   toggled on the main thread via [kotlinx.coroutines.flow.MutableStateFlow].
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** True while export or restore is in progress (PBKDF2 derivation + crypto). */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Live count of contacts in the repository. Updated whenever the DB changes. */
    val contactCount: StateFlow<Int> = MutableStateFlow(0).also { count ->
        viewModelScope.launch {
            contactRepository.allContacts.collect { count.value = it.size }
        }
    }

    fun clearStatus() { _statusMessage.value = null }

    // ─────────────────────────────────────────────────────────────────────
    // Export
    // ─────────────────────────────────────────────────────────────────────

    fun exportContacts(destUri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val contacts = withContext(Dispatchers.IO) {
                    contactRepository.allContacts.first()
                }
                if (contacts.isEmpty()) {
                    _statusMessage.value = context.getString(R.string.backup_status_no_contacts)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        BackupUtils.export(contacts, passphrase, out)
                    }
                }
                _statusMessage.value = context.resources.getQuantityString(
                    R.plurals.backup_status_export_success, contacts.size, contacts.size
                )
                Timber.i("BackupUtils: exported %d contacts", contacts.size)
            } catch (e: Exception) {
                Timber.e(e, "BackupUtils: export failed")
                _statusMessage.value = context.getString(R.string.backup_status_export_failed, e.message)
            } finally {
                passphrase.fill('\u0000')
                _isLoading.value = false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Restore
    // ─────────────────────────────────────────────────────────────────────

    fun restoreContacts(srcUri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val contacts = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(srcUri)?.use { input ->
                        BackupUtils.restore(passphrase, input)
                    }
                } ?: run {
                    _statusMessage.value = context.getString(R.string.backup_status_read_failed)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    contactRepository.upsertAll(contacts)
                }
                _statusMessage.value = context.resources.getQuantityString(
                    R.plurals.backup_status_restore_success, contacts.size, contacts.size
                )
                Timber.i("BackupUtils: restored %d contacts", contacts.size)
            } catch (e: BackupUtils.BackupException) {
                Timber.w(e, "BackupUtils: restore failed (bad passphrase or corrupted file)")
                _statusMessage.value = context.getString(R.string.backup_status_restore_wrong_passphrase)
            } catch (e: Exception) {
                Timber.e(e, "BackupUtils: restore failed unexpectedly")
                _statusMessage.value = context.getString(R.string.backup_status_restore_failed, e.message)
            } finally {
                passphrase.fill('\u0000')
                _isLoading.value = false
            }
        }
    }
}

