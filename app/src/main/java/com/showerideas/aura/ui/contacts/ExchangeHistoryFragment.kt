package com.showerideas.aura.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentExchangeHistoryBinding
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeAuditEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.button.MaterialButton

/**
 * History tab — shows all successful exchanges ordered newest-first.
 *
 * Each row surfaces the linked [Contact] (if still present in AURA) and offers:
 *  - "View" → opens [ContactDetailBottomSheet] for full profile detail.
 *  - "Add to Phone" → fires a [ContactsContract] system intent to save the
 *    contact to the device address book.
 */
@AndroidEntryPoint
class ExchangeHistoryFragment : Fragment() {

    private var _binding: FragmentExchangeHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExchangeHistoryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = HistoryAdapter(
            onView = { contact ->
                ContactDetailBottomSheet.newInstance(contact.id)
                    .show(childFragmentManager, ContactDetailBottomSheet::class.java.simpleName)
            },
            onAddToPhone = { contact -> addToPhoneContacts(contact) }
        )
        binding.rvHistory.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.historyItems.collect { items ->
                    adapter.submitList(items)
                    binding.tvHistoryEmpty.visibility =
                        if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun addToPhoneContacts(contact: Contact) {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (contact.displayName.isNotBlank())
                putExtra(ContactsContract.Intents.Insert.NAME, contact.displayName)
            if (contact.phone.isNotBlank())
                putExtra(ContactsContract.Intents.Insert.PHONE, contact.phone)
            if (contact.email.isNotBlank())
                putExtra(ContactsContract.Intents.Insert.EMAIL, contact.email)
            if (contact.company.isNotBlank())
                putExtra(ContactsContract.Intents.Insert.COMPANY, contact.company)
            if (contact.title.isNotBlank())
                putExtra(ContactsContract.Intents.Insert.JOB_TITLE, contact.title)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

private class HistoryAdapter(
    private val onView: (Contact) -> Unit,
    private val onAddToPhone: (Contact) -> Unit
) : ListAdapter<ExchangeHistoryItem, HistoryAdapter.VH>(DIFF) {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())

    inner class VH(item: View) : RecyclerView.ViewHolder(item) {
        val tvInitials: TextView    = item.findViewById(R.id.tv_initials)
        val tvName: TextView        = item.findViewById(R.id.tv_name)
        val tvTimestamp: TextView   = item.findViewById(R.id.tv_timestamp)
        val chipChannel: Chip       = item.findViewById(R.id.chip_channel)
        val btnView: MaterialButton = item.findViewById(R.id.btn_view_contact)
        val btnAdd: MaterialButton  = item.findViewById(R.id.btn_add_to_phone)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exchange_history_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val contact = item.contact

        // avatar initials — use contact name when available
        val displayName = contact?.displayName?.takeIf { it.isNotBlank() }
            ?: holder.itemView.context.getString(R.string.contact_unknown_name)
        val initials = displayName
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }

        holder.tvInitials.text = initials
        holder.tvName.text = displayName
        holder.tvTimestamp.text = dateFormat.format(Date(item.entry.timestampMs))

        // channel badge
        holder.chipChannel.text = when (item.entry.channel) {
            ExchangeAuditEntry.CHANNEL_NFC        -> "NFC"
            ExchangeAuditEntry.CHANNEL_QR         -> "QR"
            ExchangeAuditEntry.CHANNEL_ROOM_HOST,
            ExchangeAuditEntry.CHANNEL_ROOM_GUEST -> "Room"
            else                                   -> "Nearby"
        }

        // action buttons
        if (contact != null) {
            holder.btnView.visibility = View.VISIBLE
            holder.btnAdd.visibility = View.VISIBLE
            holder.btnView.setOnClickListener { onView(contact) }
            holder.btnAdd.setOnClickListener { onAddToPhone(contact) }
        } else {
            // contact was deleted from AURA — nothing actionable
            holder.btnView.visibility = View.GONE
            holder.btnAdd.visibility = View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ExchangeHistoryItem>() {
            override fun areItemsTheSame(a: ExchangeHistoryItem, b: ExchangeHistoryItem) =
                a.entry.id == b.entry.id
            override fun areContentsTheSame(a: ExchangeHistoryItem, b: ExchangeHistoryItem) =
                a == b
        }
    }
}
