package com.showerideas.aura.ui.contacts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.showerideas.aura.databinding.ItemContactBinding
import com.showerideas.aura.model.Contact
import com.showerideas.aura.utils.AvatarUtils
import java.io.File

class ContactsAdapter(
    private val onContactClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: Contact) {
            val ctx = binding.root.context
            val displayName = contact.displayName.ifBlank {
                ctx.getString(com.showerideas.aura.R.string.contact_unknown_name)
            }
            binding.tvName.text = displayName
            binding.tvSubtitle.text = when {
                contact.title.isNotBlank() && contact.company.isNotBlank() ->
                    "${contact.title} @ ${contact.company}"
                contact.email.isNotBlank() -> contact.email
                contact.phone.isNotBlank() -> contact.phone
                else -> ctx.getString(com.showerideas.aura.R.string.contact_subtitle_aura)
            }
            binding.tvInitials.text = contact.displayName
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifBlank { "?" }

            // PR-10: prefer the saved photo when present; fall back to initials.
            val avatarPath = contact.avatarUri
            val bitmap = if (avatarPath.isNotBlank()) AvatarUtils.loadBitmap(File(avatarPath)) else null
            if (bitmap != null) {
                binding.ivAvatar.setImageBitmap(bitmap)
                binding.ivAvatar.visibility = View.VISIBLE
            } else {
                binding.ivAvatar.setImageDrawable(null)
                binding.ivAvatar.visibility = View.GONE
            }

            // PR-12: small star badge next to the name when isFavorite=true.
            binding.ivFavouriteBadge.visibility =
                if (contact.isFavorite) View.VISIBLE else View.GONE

            // PR-17: use the pre-translated string resources so TalkBack
            // descriptions are localised rather than hardcoded English.
            binding.root.contentDescription = if (contact.isFavorite) {
                ctx.getString(
                    com.showerideas.aura.R.string.contact_a11y_row_favourite,
                    displayName, binding.tvSubtitle.text
                )
            } else {
                ctx.getString(
                    com.showerideas.aura.R.string.contact_a11y_row,
                    displayName, binding.tvSubtitle.text
                )
            }

            binding.root.setOnClickListener { onContactClick(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(old: Contact, new: Contact) = old.id == new.id
        override fun areContentsTheSame(old: Contact, new: Contact) = old == new
    }
}
