package com.showerideas.aura.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.showerideas.aura.databinding.ItemBlockedEndpointBinding
import com.showerideas.aura.model.BlockedEndpoint
import java.text.DateFormat
import java.util.Date

/**
 * PR-19: ListAdapter for the blocked-devices screen. Renders endpoint ID
 * (truncated to a readable length) + the block time, with an inline
 * Unblock action.
 */
class BlockedDevicesAdapter(
    private val onUnblock: (BlockedEndpoint) -> Unit
) : ListAdapter<BlockedEndpoint, BlockedDevicesAdapter.VH>(DiffCallback) {

    inner class VH(private val binding: ItemBlockedEndpointBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BlockedEndpoint) {
            // Show the user-attached note when present (typically the
            // contact's display name at block time), otherwise fall back
            // to the truncated endpoint ID.
            binding.tvEndpointLabel.text = if (item.note.isNotBlank()) {
                item.note
            } else {
                item.endpointId.take(12) + if (item.endpointId.length > 12) "…" else ""
            }
            binding.tvBlockedAt.text =
                DateFormat.getDateTimeInstance().format(Date(item.blockedAt))
            binding.btnUnblock.setOnClickListener { onUnblock(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemBlockedEndpointBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<BlockedEndpoint>() {
        override fun areItemsTheSame(o: BlockedEndpoint, n: BlockedEndpoint) =
            o.endpointId == n.endpointId
        override fun areContentsTheSame(o: BlockedEndpoint, n: BlockedEndpoint) = o == n
    }
}
