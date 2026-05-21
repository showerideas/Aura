package com.showerideas.aura.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.showerideas.aura.databinding.BottomSheetContactDetailBinding
import com.showerideas.aura.utils.toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Bottom sheet showing full contact detail with quick-action buttons.
 */
@AndroidEntryPoint
class ContactDetailBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CONTACT_ID = "contact_id"

        fun newInstance(contactId: String) = ContactDetailBottomSheet().apply {
            arguments = bundleOf(ARG_CONTACT_ID to contactId)
        }
    }

    private var _binding: BottomSheetContactDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ContactDetailViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetContactDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val contactId = arguments?.getString(ARG_CONTACT_ID) ?: return
        viewModel.loadContact(contactId)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.contact.collect { contact ->
                    contact ?: return@collect
                    binding.tvName.text = contact.displayName.ifBlank { "Unknown" }
                    binding.tvTitle.text = buildString {
                        if (contact.title.isNotBlank()) append(contact.title)
                        if (contact.title.isNotBlank() && contact.company.isNotBlank()) append(" @ ")
                        if (contact.company.isNotBlank()) append(contact.company)
                    }
                    binding.tvEmail.text = contact.email.ifBlank { "-" }
                    binding.tvPhone.text = contact.phone.ifBlank { "-" }
                    binding.tvWebsite.text = contact.website.ifBlank { "-" }
                    binding.tvBio.text = contact.bio

                    binding.btnCall.isEnabled = contact.phone.isNotBlank()
                    binding.btnEmail.isEnabled = contact.email.isNotBlank()
                    binding.btnCopy.isEnabled = true

                    binding.btnCall.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}")))
                    }
                    binding.btnEmail.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${contact.email}")))
                    }
                    binding.btnCopy.setOnClickListener {
                        val text = buildString {
                            appendLine(contact.displayName)
                            if (contact.phone.isNotBlank()) appendLine(contact.phone)
                            if (contact.email.isNotBlank()) appendLine(contact.email)
                            if (contact.company.isNotBlank()) appendLine(contact.company)
                        }
                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Contact", text))
                        requireContext().toast("Copied to clipboard")
                    }
                    binding.btnDelete.setOnClickListener {
                        viewModel.deleteContact(contact)
                        dismiss()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
