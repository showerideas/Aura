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
 * ViewModel for the on-device exchange analytics screen.
 * All data is derived from local [ExchangeAuditEntry] records — zero telemetry.
 */
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val auditRepository: ExchangeAuditRepository
) : ViewModel() {

    data class AnalyticsState(
        val weeklyExchangeCounts: List<Pair<String, Int>> = emptyList(),  // day label → count
        val topContacts: List<Pair<String, Int>> = emptyList(),           // identity key hash → count
        val successRate: Float = 0f,
        val totalExchanges: Int = 0
    )

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    init {
        loadAnalytics()
    }

    private fun loadAnalytics() {
        viewModelScope.launch {
            auditRepository.allEntries.collect { entries ->
                if (entries.isEmpty()) {
                    _state.value = AnalyticsState()
                    return@collect
                }
                val now = System.currentTimeMillis()
                val weekAgo = now - 7L * 24 * 60 * 60 * 1000

                // Weekly counts by day (last 7 days)
                val weeklyEntries = entries.filter { it.timestampMs >= weekAgo }
                val dayBuckets = mutableMapOf<String, Int>()
                weeklyEntries.forEach { entry ->
                    val label = android.text.format.DateFormat.format("E", entry.timestampMs).toString()
                    dayBuckets[label] = (dayBuckets[label] ?: 0) + 1
                }

                // Top-10 recurring contacts (by identity key hash)
                val contactCounts: List<Pair<String, Int>> = entries
                    .groupBy { it.peerIdentityKeyHash ?: "Unknown" }
                    .mapValues { (_, v) -> v.size }
                    .entries
                    .sortedByDescending { (_, count) -> count }
                    .take(10)
                    .map { (key, count) -> key to count }

                // Success rate
                val successCount = entries.count { it.outcome == "SUCCESS" }
                val successRate = if (entries.isNotEmpty()) successCount.toFloat() / entries.size else 0f

                _state.value = AnalyticsState(
                    weeklyExchangeCounts = dayBuckets.entries.map { (k, v) -> k to v },
                    topContacts = contactCounts,
                    successRate = successRate,
                    totalExchanges = entries.size
                )
            }
        }
    }

    fun exportToCsv(outputFile: File) {
        viewModelScope.launch {
            auditRepository.allEntries.collect { entries ->
                val sb = StringBuilder("timestamp,peerKeyHash,outcome,channel\n")
                entries.forEach { e ->
                    sb.append("${e.timestampMs},${e.peerIdentityKeyHash ?: ""},${e.outcome},${e.channel}\n")
                }
                outputFile.writeText(sb.toString())
            }
        }
    }
}
