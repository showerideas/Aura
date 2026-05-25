package com.showerideas.aura.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.showerideas.aura.R
import com.showerideas.aura.databinding.ItemProfileCardBinding
import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.ProfileType

/**
 * RecyclerView adapter for the profile switcher bottom sheet.
 *
 * Displays each profile as a card row. The active profile shows an "Active" badge;
 * non-active profiles show a "Set as active" affordance on tap.
 *
 * Delete button is surfaced on non-active, non-only profiles via [onDelete].
 */
class ProfileSwitcherAdapter(
    private val onSetActive: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit
) : ListAdapter<Profile, ProfileSwitcherAdapter.ProfileVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileVH {
        val binding = ItemProfileCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ProfileVH(binding)
    }

    override fun onBindViewHolder(holder: ProfileVH, position: Int) {
        holder.bind(getItem(position), itemCount)
    }

    inner class ProfileVH(private val binding: ItemProfileCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: Profile, totalCount: Int) {
            val ctx = binding.root.context

            // Profile type chip label + color
            val (typeName, chipColor) = when (profile.profileType) {
                ProfileType.PERSONAL -> ctx.getString(R.string.profile_type_personal) to
                        ctx.getColor(R.color.aura_purple)
                ProfileType.WORK     -> ctx.getString(R.string.profile_type_work) to
                        ctx.getColor(R.color.aura_cyan)
                ProfileType.CUSTOM   -> {
                    val label = profile.customLabel.ifBlank { ctx.getString(R.string.profile_type_custom) }
                    label to ctx.getColor(R.color.on_surface_secondary)
                }
            }
            binding.chipProfileType.text = typeName
            binding.chipProfileType.setChipBackgroundColorResource(
                if (profile.profileType == ProfileType.WORK) R.color.aura_cyan else R.color.aura_purple
            )
            binding.chipProfileType.setTextColor(ctx.getColor(android.R.color.white))

            // Display name — fallback to type name if empty
            binding.tvProfileName.text =
                profile.displayName.ifBlank { typeName }

            // Active state
            if (profile.isActive) {
                binding.tvActiveBadge.visibility = View.VISIBLE
                binding.tvProfileHint.visibility = View.VISIBLE
                binding.btnDeleteProfile.visibility = View.GONE
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
            } else {
                binding.tvActiveBadge.visibility = View.GONE
                binding.tvProfileHint.visibility = View.GONE
                // Show delete only when there's more than one profile
                binding.btnDeleteProfile.visibility =
                    if (totalCount > 1) View.VISIBLE else View.GONE
                binding.root.isClickable = true
                binding.root.setOnClickListener { onSetActive(profile) }
                binding.btnDeleteProfile.setOnClickListener { onDelete(profile) }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Profile>() {
            override fun areItemsTheSame(old: Profile, new: Profile) = old.id == new.id
            override fun areContentsTheSame(old: Profile, new: Profile) = old == new
        }
    }
}
