package com.showerideas.aura.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.showerideas.aura.utils.AvatarUtils
import com.showerideas.aura.utils.shareVCard
import com.showerideas.aura.utils.toVCard
import com.showerideas.aura.utils.toast
import java.io.File
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
                    binding.tvName.text = contact.displayName.ifBlank {
                        getString(com.showerideas.aura.R.string.contact_unknown_name)
                    }

                    // PR-10: show the peer's avatar bitmap when present.
                    val avatarBitmap = contact.avatarUri
                        .takeIf { it.isNotBlank() }
                        ?.let { AvatarUtils.loadBitmap(File(it)) }
                    if (avatarBitmap != null) {
                        binding.ivAvatar.setImageBitmap(avatarBitmap)
                        binding.ivAvatar.visibility = View.VISIBLE
                    } else {
                        binding.ivAvatar.visibility = View.GONE
                    }
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

                    // PR-17: dynamic TalkBack labels that include the contact
                    // name so reading down the action row is intelligible.
                    // PR-20: pulled the format strings into strings.xml so
                    // they translate as a unit.
                    // Note: `R.string` is a Kotlin class, not an object, so it
                    // cannot be aliased to a `val`. Reference resources via
                    // the fully-qualified `R.string.*` form below.
                    val nameForA11y = contact.displayName.ifBlank {
                        getString(com.showerideas.aura.R.string.contact_unknown_name)
                    }
                    binding.btnCall.contentDescription =
                        getString(com.showerideas.aura.R.string.contact_a11y_call, nameForA11y)
                    binding.btnEmail.contentDescription =
                        getString(com.showerideas.aura.R.string.contact_a11y_email, nameForA11y)
                    binding.btnCopy.contentDescription =
                        getString(com.showerideas.aura.R.string.contact_a11y_copy, nameForA11y)
                    binding.btnExport.contentDescription =
                        getString(com.showerideas.aura.R.string.contact_a11y_export, nameForA11y)
                    binding.btnDelete.contentDescription =
                        getString(com.showerideas.aura.R.string.contact_a11y_delete, nameForA11y)
                    binding.btnBlock.contentDescription =
                        getString(com.showerideas.aura.R.string.contact_a11y_block)
                    binding.btnFavourite.contentDescription =
                        if (contact.isFavorite)
                            getString(com.showerideas.aura.R.string.contact_a11y_unmark_favourite, nameForA11y)
                        else
                            getString(com.showerideas.aura.R.string.contact_a11y_mark_favourite, nameForA11y)

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
                        requireContext().toast(getString(com.showerideas.aura.R.string.contact_copied))
                    }
                    binding.btnExport.setOnClickListener {
                        // PR-07: export this contact as a .vcf via FileProvider.
                        requireContext().shareVCard(
                            contact.toVCard(),
                            contact.displayName.ifBlank { "contact" }
                        )
                    }
                    binding.btnDelete.setOnClickListener {
                        viewModel.deleteContact(contact)
                        dismiss()
                    }

                    // PR-14: block-this-device action. Guarded by a
                    // confirmation dialog because it's irreversible from
                    // here — unblocking lives in Settings (PR-19).
                    binding.btnBlock.setOnClickListener {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(com.showerideas.aura.R.string.contact_block_dialog_title)
                            .setMessage(com.showerideas.aura.R.string.contact_block_dialog_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(com.showerideas.aura.R.string.contact_block_dialog_confirm) { _, _ ->
                                // FIX-5: pass identityKeyHash so the block keys on
                                // stable identity, not the ephemeral endpoint ID.
                                viewModel.blockEndpoint(
                                    contact.sourceEndpointId,
                                    identityKeyHash = contact.identityKeyHash,
                                    note = contact.displayName
                                )
                                dismiss()
                            }
                            .show()
                    }

                    // PR-12: favourite toggle. Re-observing viewModel.contact
                    // is handled by this same collector — toggleFavorite
                    // pushes the updated copy back into _contact so the icon
                    // refreshes on the next emission without dismissing.
                    binding.btnFavourite.setImageResource(
                        if (contact.isFavorite) com.showerideas.aura.R.drawable.ic_star
                        else com.showerideas.aura.R.drawable.ic_star_border
                    )
                    binding.btnFavourite.setOnClickListener {
                        viewModel.toggleFavorite(contact)
                    }

                    // PR-12: inline notes. Pre-populate on every contact load
                    // (incl. after toggleFavorite which refreshes _contact)
                    // but don't reattach the TextWatcher every emission —
                    // we attach it once below.
                    if (binding.etNotes.text?.toString() != contact.notes) {
                        binding.etNotes.setText(contact.notes)
                    }
                    binding.btnSaveNote.setOnClickListener {
                        viewModel.saveNote(contact, binding.etNotes.text?.toString().orEmpty())
                        binding.btnSaveNote.visibility = View.GONE
                    }
                }
            }
        }

        // PR-12: surface the Save-note button only when the text differs from
        // the persisted value. Attached once so we don't stack watchers on
        // every emission of viewModel.contact.
        binding.etNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString().orEmpty()
                val saved = viewModel.contact.value?.notes.orEmpty()
                binding.btnSaveNote.visibility =
                    if (current != saved) View.VISIBLE else View.GONE
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
