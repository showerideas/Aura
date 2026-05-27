package com.showerideas.aura.ui.audit

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.showerideas.aura.R
import com.showerideas.aura.databinding.ItemAuditEntryBinding
import com.showerideas.aura.model.ExchangeAuditEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the exchange audit log screen.
 *
 * Each row shows: timestamp, outcome chip (colour-coded), channel chip,
 * peer identity hash (truncated to 16 chars), and error code if present.
 */
class AuditAdapter : ListAdapter<ExchangeAuditEntry, AuditAdapter.AuditVH>(DIFF) {

    private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AuditVH {
        val binding = ItemAuditEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AuditVH(binding)
    }

    override fun onBindViewHolder(holder: AuditVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AuditVH(private val binding: ItemAuditEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ExchangeAuditEntry) {
            val ctx = binding.root.context

            // Timestamp
            binding.tvTimestamp.text = timeFmt.format(Date(entry.timestampMs))

            // Outcome chip
            val (outcomeLabel, outcomeTint) = when (entry.outcome) {
                ExchangeAuditEntry.OUTCOME_SUCCESS -> ctx.getString(R.string.audit_outcome_success) to
                        ctx.getColor(R.color.aura_cyan)
                ExchangeAuditEntry.OUTCOME_FAILED  -> ctx.getString(R.string.audit_outcome_failed) to
                        ctx.getColor(R.color.error)
                ExchangeAuditEntry.OUTCOME_BLOCKED -> ctx.getString(R.string.audit_outcome_blocked) to
                        ctx.getColor(R.color.on_surface_secondary)
                ExchangeAuditEntry.OUTCOME_SPOOF   -> ctx.getString(R.string.audit_outcome_spoof) to
                        ctx.getColor(R.color.error)
                ExchangeAuditEntry.OUTCOME_TIMEOUT -> ctx.getString(R.string.audit_outcome_timeout) to
                        ctx.getColor(R.color.on_surface_secondary)
                "SAS_CONFIRMED"                    -> ctx.getString(R.string.audit_outcome_sas_confirmed) to
                        ctx.getColor(R.color.aura_purple_light)
                else                               -> entry.outcome to ctx.getColor(R.color.on_surface_secondary)
            }
            binding.chipOutcome.text = outcomeLabel
            binding.chipOutcome.setChipBackgroundColorResource(android.R.color.transparent)
            binding.chipOutcome.setTextColor(outcomeTint)
            binding.chipOutcome.chipStrokeWidth = 1f
            binding.chipOutcome.setChipStrokeColorResource(android.R.color.transparent)

            // Channel chip
            val channelLabel = when (entry.channel) {
                ExchangeAuditEntry.CHANNEL_NEARBY     -> ctx.getString(R.string.audit_channel_nearby)
                ExchangeAuditEntry.CHANNEL_NFC        -> ctx.getString(R.string.audit_channel_nfc)
                ExchangeAuditEntry.CHANNEL_QR         -> ctx.getString(R.string.audit_channel_qr)
                ExchangeAuditEntry.CHANNEL_ROOM_HOST  -> ctx.getString(R.string.audit_channel_room_host)
                ExchangeAuditEntry.CHANNEL_ROOM_GUEST -> ctx.getString(R.string.audit_channel_room_guest)
                else                                  -> entry.channel
            }
            binding.chipChannel.text = channelLabel

            // Peer identity hash
            val peerText = entry.peerIdentityKeyHash
                ?.take(16)
                ?.let { "$it…" }
                ?: ctx.getString(R.string.audit_peer_unknown)
            binding.tvPeer.text = peerText

            // Error code (visible only when present)
            if (entry.errorCode != null) {
                binding.tvErrorCode.text = entry.errorCode
                binding.tvErrorCode.visibility = View.VISIBLE
            } else {
                binding.tvErrorCode.visibility = View.GONE
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ExchangeAuditEntry>() {
            override fun areItemsTheSame(old: ExchangeAuditEntry, new: ExchangeAuditEntry) =
                old.id == new.id
            override fun areContentsTheSame(old: ExchangeAuditEntry, new: ExchangeAuditEntry) =
                old == new
        }
    }
}
