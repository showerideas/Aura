package com.showerideas.aura.ui.exchange

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.showerideas.aura.R
import com.showerideas.aura.databinding.BottomSheetExchangeSuccessBinding
import com.showerideas.aura.model.Contact
import com.showerideas.aura.ui.contacts.ContactDetailBottomSheet
import com.showerideas.aura.ui.contacts.ContactDetailViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Bottom sheet shown immediately after a successful contact exchange.
 *
 * Displays the received contact(s) with name + phone/email.
 * Tapping a row opens [ContactDetailBottomSheet] for full detail.
 * The ✕ button dismisses the sheet and signals the host fragment to navigate home.
 */
@AndroidEntryPoint
class ExchangeSuccessBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ExchangeSuccessBottomSheet"
        private const val ARG_CONTACT_ID = "contact_id"

        fun newInstance(contactId: String) = ExchangeSuccessBottomSheet().apply {
            arguments = bundleOf(ARG_CONTACT_ID to contactId)
        }
    }

    private var _binding: BottomSheetExchangeSuccessBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContactDetailViewModel by viewModels()

    /** Called by the host fragment when the sheet is dismissed via ✕ so it can navigate home. */
    var onClose: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExchangeSuccessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val contactId = arguments?.getString(ARG_CONTACT_ID) ?: return
        viewModel.loadContact(contactId)

        val adapter = SuccessContactAdapter { contact ->
            ContactDetailBottomSheet.newInstance(contact.id)
                .show(parentFragmentManager, ContactDetailBottomSheet::class.java.simpleName)
        }
        binding.rvSharedContacts.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.contact.collect { contact ->
                    contact ?: return@collect
                    adapter.submitList(listOf(contact))
                }
            }
        }

        binding.btnClose.setOnClickListener {
            dismiss()
            onClose?.invoke()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter
// ─────────────────────────────────────────────────────────────────────────────

private class SuccessContactAdapter(
    private val onTap: (Contact) -> Unit
) : ListAdapter<Contact, SuccessContactAdapter.VH>(DIFF) {

    inner class VH(item: View) : RecyclerView.ViewHolder(item) {
        val tvInitials: TextView = item.findViewById(R.id.tv_initials)
        val tvName: TextView = item.findViewById(R.id.tv_name)
        val tvContactInfo: TextView = item.findViewById(R.id.tv_contact_info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exchange_success_contact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val contact = getItem(position)
        val initials = contact.displayName
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }

        holder.tvInitials.text = initials
        holder.tvName.text = contact.displayName.ifBlank {
            holder.itemView.context.getString(R.string.contact_unknown_name)
        }
        holder.tvContactInfo.text = when {
            contact.phone.isNotBlank() && contact.email.isNotBlank() ->
                "${contact.phone}  ·  ${contact.email}"
            contact.phone.isNotBlank() -> contact.phone
            contact.email.isNotBlank() -> contact.email
            else -> ""
        }
        holder.itemView.setOnClickListener { onTap(contact) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Contact>() {
            override fun areItemsTheSame(a: Contact, b: Contact) = a.id == b.id
            override fun areContentsTheSame(a: Contact, b: Contact) = a == b
        }
    }
}
