package com.showerideas.aura.ui.audit

import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentAnalyticsBinding
import com.showerideas.aura.data.ExchangeAuditRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Phase 9.2 — On-device analytics screen.
 *
 * Surfaces aggregate insights derived entirely from local [ExchangeAuditEntry] records.
 * No data leaves the device. Sections:
 *
 *   - Summary: total exchange count + success rate.
 *   - Weekly chart: horizontal bar-per-day for the last 7 days.
 *   - Top contacts: top-10 by exchange frequency (identity key hash, truncated).
 *   - CSV export: writes the full audit log to Downloads/.
 *
 * Navigation: AuditFragment toolbar → "Analytics" action.
 */
@AndroidEntryPoint
class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AnalyticsViewModel by viewModels()

    @Inject lateinit var auditRepository: ExchangeAuditRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarAnalytics.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.btnExportCsv.setOnClickListener { exportCsv() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private fun renderState(state: AnalyticsViewModel.AnalyticsState) {
        if (state.totalExchanges == 0) {
            binding.tvAnalyticsEmpty.visibility = View.VISIBLE
            binding.cardSummary.visibility = View.GONE
            return
        }
        binding.tvAnalyticsEmpty.visibility = View.GONE
        binding.cardSummary.visibility = View.VISIBLE

        // Summary row
        binding.tvTotalExchanges.text = state.totalExchanges.toString()
        binding.tvSuccessRate.text = if (state.totalExchanges > 0) {
            "${(state.successRate * 100).toInt()}%"
        } else "—"

        // Weekly chart
        renderWeeklyChart(state.weeklyExchangeCounts)

        // Top contacts
        renderTopContacts(state.topContacts)
    }

    private fun renderWeeklyChart(data: List<Pair<String, Int>>) {
        val container = binding.llWeeklyChart
        container.removeAllViews()
        if (data.isEmpty()) {
            container.addView(makeSubtitle(getString(R.string.analytics_no_data_this_week)))
            return
        }
        val maxCount = data.maxOfOrNull { it.second } ?: 1
        data.sortedBy { it.first }.forEach { (day, count) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6.dp }
            }

            val label = TextView(requireContext()).apply {
                text = day
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_secondary))
                layoutParams = LinearLayout.LayoutParams(48.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val bar = LinearProgressIndicator(requireContext()).apply {
                max = maxCount
                progress = count
                trackColor = ContextCompat.getColor(requireContext(), R.color.surface_variant)
                setIndicatorColor(ContextCompat.getColor(requireContext(), R.color.aura_cyan))
                layoutParams = LinearLayout.LayoutParams(0, 16.dp, 1f).also {
                    it.marginStart = 8.dp
                    it.marginEnd = 8.dp
                }
            }

            val countLabel = TextView(requireContext()).apply {
                text = count.toString()
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                layoutParams = LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            row.addView(label); row.addView(bar); row.addView(countLabel)
            container.addView(row)
        }
    }

    private fun renderTopContacts(contacts: List<Pair<String, Int>>) {
        val container = binding.llTopContacts
        container.removeAllViews()
        if (contacts.isEmpty()) {
            container.addView(makeSubtitle(getString(R.string.analytics_no_recurring)))
            return
        }
        contacts.forEachIndexed { idx, (keyHash, count) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 4.dp }
            }

            val rank = TextView(requireContext()).apply {
                text = "${idx + 1}."
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.aura_purple_light))
                layoutParams = LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val keyDisplay = keyHash.take(12) + if (keyHash.length > 12) "…" else ""
            val contactLabel = TextView(requireContext()).apply {
                text = keyDisplay
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.marginStart = 8.dp
                }
            }

            val countLabel = TextView(requireContext()).apply {
                text = resources.getQuantityString(R.plurals.analytics_exchanges_count, count, count)
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_secondary))
            }

            row.addView(rank); row.addView(contactLabel); row.addView(countLabel)
            container.addView(row)
        }
    }

    // -------------------------------------------------------------------------
    // CSV export
    // -------------------------------------------------------------------------

    private fun exportCsv() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = auditRepository.allEntries.first()
            if (entries.isEmpty()) {
                Toast.makeText(requireContext(), R.string.analytics_export_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val fileName = "aura_analytics_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            }.csv"

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = requireContext().contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: run {
                    Toast.makeText(requireContext(), R.string.analytics_export_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }

            resolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write("timestamp,peer_key_hash,outcome,channel,duration_ms\n")
                    entries.forEach { e ->
                        writer.write("${e.timestampMs},${e.peerIdentityKeyHash ?: ""},${e.outcome},${e.channel},${e.durationMs ?: ""}\n")
                    }
                }
            }

            Toast.makeText(requireContext(), R.string.analytics_export_done, Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeSubtitle(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_secondary))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = 8.dp }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
