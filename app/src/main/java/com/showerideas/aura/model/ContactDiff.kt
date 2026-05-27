package com.showerideas.aura.model

import com.showerideas.aura.model.Contact

/**
 * A single field-level diff between an existing [Contact] and an incoming
 * updated version of the same contact (same [Contact.identityKeyHash]).
 *
 * @param field      Machine-readable field identifier (e.g. "displayName").
 * @param label      Human-readable field label for display in the merge UI.
 * @param oldValue   The value currently saved in Room.
 * @param newValue   The value received in the incoming exchange.
 */
data class ContactFieldDiff(
    val field: String,
    val label: String,
    val oldValue: String,
    val newValue: String
)

/**
 * Encapsulates a merge situation: an incoming exchange from a known peer
 * (matched by [Contact.identityKeyHash]) whose fields differ from the stored record.
 *
 * After the exchange completes and the contact is silently updated in Room,
 * this object is surfaced to the UI so the user can review what changed and
 * optionally revert individual fields.
 *
 * @param preserved  The merged contact as it was saved to Room (new field values).
 * @param previous   The contact as it was before the exchange (old field values).
 * @param diffs      The list of fields that changed — empty if all fields are identical.
 */
data class MergeEvent(
    val preserved: Contact,
    val previous: Contact,
    val diffs: List<ContactFieldDiff>
) {
    /** True when at least one visible field changed. */
    val hasChanges: Boolean get() = diffs.isNotEmpty()
}

