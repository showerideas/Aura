package com.showerideas.aura.ui.audit

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ExchangeAuditRepository
import com.showerideas.aura.model.ExchangeAuditEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the exchange audit log screen.
 *
 * Exposes all audit entries as a [StateFlow] and provides CSV export
 * and clear operations.
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    private val auditRepository: ExchangeAuditRepository
) : ViewModel() {

    val entries: StateFlow<List<ExchangeAuditEntry>> = auditRepository.allEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Clear all audit log entries. */
    fun clearAll() {
        viewModelScope.launch { auditRepository.clearAll() }
    }

    /**
     * Export the current entries to a CSV file in the public Downloads directory.
     *
     * Returns the [File] written on success, or null if the write failed.
     * Caller should display a toast based on the return value.
     */
    fun exportToCsv(context: Context): File? {
        val currentEntries = entries.value
        if (currentEntries.isEmpty()) return null

        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val header = "timestamp,outcome,channel,direction,peerHash,errorCode\n"
            val rows = currentEntries.joinToString(separator = "\n") { e ->
                listOf(
                    fmt.format(Date(e.timestampMs)),
                    e.outcome,
                    e.channel,
                    e.direction,
                    e.peerIdentityKeyHash?.take(16) ?: "",
                    e.errorCode ?: ""
                ).joinToString(",") { cell ->
                    // Basic CSV escaping: wrap in quotes if contains comma or quote
                    if (cell.contains(',') || cell.contains('"')) "\"${cell.replace("\"", "\"\"")}\"" else cell
                }
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(downloadsDir, "aura_exchange_log_$timestamp.csv")
            file.writeText(header + rows, Charsets.UTF_8)
            Timber.i("Audit log exported to ${file.absolutePath} (${currentEntries.size} entries)")
            file
        }.onFailure { e ->
            Timber.e(e, "Failed to export audit log to CSV")
        }.getOrNull()
    }
}
