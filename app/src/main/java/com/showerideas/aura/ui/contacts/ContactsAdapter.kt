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
            binding.tvName.text = contact.displayName.ifBlank { "Unknown" }
            binding.tvSubtitle.text = when {
                contact.title.isNotBlank() && contact.company.isNotBlank() ->
                    "${contact.title} @ ${contact.company}"
                contact.email.isNotBlank() -> contact.email
                contact.phone.isNotBlank() -> contact.phone
                else -> "AURA contact"
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

            // PR-17: synthesise a TalkBack-friendly description that
            // includes the favourite state and tells the user the row is
            // tappable. The interior TextViews remain unannotated to
            // avoid double-announcement.
            val favSuffix = if (contact.isFavorite) ", favourite" else ""
            binding.root.contentDescription =
                "${binding.tvName.text}$favSuffix, ${binding.tvSubtitle.text}, double tap to view details"

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
