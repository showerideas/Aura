package com.showerideas.aura.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ExchangeAuditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Phase 9.2 — ViewModel for the on-device exchange analytics screen.
 * All data is derived from local [ExchangeAuditEntry] records — zero telemetry.
 */
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val auditRepository: ExchangeAuditRepository
) : ViewModel() {

    data class AnalyticsState(
        val weeklyExchangeCounts: List<Pair<String, Int>> = emptyList(),  // day label → count
        val topContacts: List<Pair<String, Int>> = emptyList(),           // name → count
        val successRate: Float = 0f,
        val avgSessionDurationMs: Long = 0L,
        val totalExchanges: Int = 0
    )

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    init {
        loadAnalytics()
    }

    private fun loadAnalytics() {
        viewModelScope.launch {
            auditRepository.getAllEntries().collect { entries ->
                if (entries.isEmpty()) {
                    _state.value = AnalyticsState()
                    return@collect
                }
                val now = System.currentTimeMillis()
                val weekAgo = now - 7L * 24 * 60 * 60 * 1000

                // Weekly counts by day (last 7 days)
                val weeklyEntries = entries.filter { it.startedAt >= weekAgo }
                val dayBuckets = mutableMapOf<String, Int>()
                weeklyEntries.forEach { entry ->
                    val label = android.text.format.DateFormat.format("E", entry.startedAt).toString()
                    dayBuckets[label] = (dayBuckets[label] ?: 0) + 1
                }

                // Top-10 recurring contacts
                val contactCounts = entries
                    .groupBy { it.peerDisplayName ?: "Unknown" }
                    .mapValues { it.value.size }
                    .entries.sortedByDescending { it.value }
                    .take(10)
                    .map { it.key to it.value }

                // Success rate
                val successCount = entries.count { it.outcome == "SUCCESS" }
                val successRate = if (entries.isNotEmpty()) successCount.toFloat() / entries.size else 0f

                // Average session duration
                val durations = entries.mapNotNull {
                    if (it.durationMs > 0) it.durationMs else null
                }
                val avgDuration = if (durations.isNotEmpty()) durations.average().toLong() else 0L

                _state.value = AnalyticsState(
                    weeklyExchangeCounts = dayBuckets.entries.map { it.key to it.value },
                    topContacts = contactCounts,
                    successRate = successRate,
                    avgSessionDurationMs = avgDuration,
                    totalExchanges = entries.size
                )
            }
        }
    }

    fun exportToCsv(outputFile: File) {
        viewModelScope.launch {
            auditRepository.getAllEntries().collect { entries ->
                val sb = StringBuilder("timestamp,peer,outcome,durationMs\n")
                entries.forEach { e ->
                    sb.append("${e.startedAt},${e.peerDisplayName ?: ""},${e.outcome},${e.durationMs}\n")
                }
                outputFile.writeText(sb.toString())
            }
        }
    }
}
