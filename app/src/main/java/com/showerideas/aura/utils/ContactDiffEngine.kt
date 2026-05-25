package com.showerideas.aura.utils

import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ContactFieldDiff
import com.showerideas.aura.model.MergeEvent

/**
 * Computes a field-level diff between two [Contact] instances that represent the
 * same person (same [Contact.identityKeyHash]).
 *
 * ## What is compared
 * Only user-visible profile fields are diffed. Internal record-keeping fields
 * ([Contact.id], [Contact.receivedAt], [Contact.sourceEndpointId], etc.) are
 * excluded because they are not part of the person's vCard and the user cannot
 * meaningfully compare them.
 *
 * ## What is NOT compared
 * - [Contact.id] — stable UUID, never changes
 * - [Contact.receivedAt] — always newer in the incoming contact
 * - [Contact.isFavorite] — user preference, preserved during deduplication
 * - [Contact.notes] — user-authored, preserved during deduplication
 * - [Contact.rssiAtExchange] — hardware measurement
 * - [Contact.sourceEndpointId] — transport detail
 * - [Contact.identityKeyHash] — must be equal for this function to be called
 * - [Contact.avatarUri] — compared as a boolean (changed/unchanged) because
 *   the actual URIs differ across sessions even for the same image
 *
 * ## Output
 * A [MergeEvent] whose [MergeEvent.diffs] list contains one [ContactFieldDiff]
 * per changed field. If all visible fields are identical, [MergeEvent.hasChanges]
 * is false and the caller should suppress the merge dialog.
 */
object ContactDiffEngine {

    /**
     * Compute the diff between [previous] (stored in Room) and [incoming]
     * (received from the latest exchange).
     *
     * Both contacts must have the same [Contact.identityKeyHash].
     * The [MergeEvent.preserved] field is set to [incoming] as the authoritative
     * post-merge record (the caller has already saved it to Room via [saveDeduped]).
     */
    fun diff(previous: Contact, incoming: Contact): MergeEvent {
        val diffs = mutableListOf<ContactFieldDiff>()

        fun check(field: String, label: String, old: String, new: String) {
            if (old.trim() != new.trim()) {
                diffs.add(ContactFieldDiff(field = field, label = label, oldValue = old, newValue = new))
            }
        }

        check("displayName", "Name",    previous.displayName, incoming.displayName)
        check("phone",       "Phone",   previous.phone,       incoming.phone)
        check("email",       "Email",   previous.email,       incoming.email)
        check("company",     "Company", previous.company,     incoming.company)
        check("title",       "Title",   previous.title,       incoming.title)
        check("website",     "Website", previous.website,     incoming.website)
        check("bio",         "Bio",     previous.bio,         incoming.bio)

        // Avatar: compare by presence, not URI (URIs always differ across sessions)
        val oldHasAvatar = previous.avatarUri.isNotBlank()
        val newHasAvatar = incoming.avatarUri.isNotBlank()
        if (oldHasAvatar != newHasAvatar) {
            diffs.add(
                ContactFieldDiff(
                    field    = "avatarUri",
                    label    = "Photo",
                    oldValue = if (oldHasAvatar) "(photo)" else "(none)",
                    newValue = if (newHasAvatar) "(photo)" else "(none)"
                )
            )
        }

        return MergeEvent(preserved = incoming, previous = previous, diffs = diffs)
    }

    /**
     * Apply [selections] to [base], replacing each field named in [selections]
     * with the chosen value. Returns a new [Contact] with the selected values.
     *
     * Used when the user picks per-field in the merge dialog: for each
     * [ContactFieldDiff] in [MergeEvent.diffs], [selections] maps the field name
     * to the chosen value (old or new).
     *
     * Fields not present in [selections] are taken from [base] unchanged.
     */
    fun applySelections(base: Contact, selections: Map<String, String>): Contact {
        return base.copy(
            displayName = selections["displayName"] ?: base.displayName,
            phone       = selections["phone"]       ?: base.phone,
            email       = selections["email"]       ?: base.email,
            company     = selections["company"]     ?: base.company,
            title       = selections["title"]       ?: base.title,
            website     = selections["website"]     ?: base.website,
            bio         = selections["bio"]         ?: base.bio,
            // avatarUri is handled separately (binary present/absent choice)
            avatarUri   = selections["avatarUri"]   ?: base.avatarUri,
        )
    }
}
