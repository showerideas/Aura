package com.showerideas.aura.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.showerideas.aura.R
import com.showerideas.aura.databinding.BottomSheetContactMergeBinding
import com.showerideas.aura.model.ContactFieldDiff
import com.showerideas.aura.model.MergeEvent

/**
 * Bottom sheet displayed after a contact update is detected via deduplication.
 *
 * When the same peer (matched by [Contact.identityKeyHash]) exchanges a second
 * time, their contact record is silently updated in Room by [ContactRepository.saveDeduped].
 * This sheet surfaces the diff so the user can review which fields changed and
 * optionally revert individual fields back to their previous values.
 *
 * Usage
 * ```kotlin
 * val event: MergeEvent = // from ContactRepository.saveDeduped()
 * ContactMergeBottomSheet.newInstance(event).show(supportFragmentManager, TAG)
 * ```
 *
 * Field-level selection
 * Each diff row contains a [SwitchMaterial]:
 * - **ON (default)**: keep the new value (already saved in Room)
 * - **OFF**: revert to the old value
 *
 * On "Accept changes": persist whichever switches are ON (no-op — new values
 * are already in Room from [saveDeduped]).
 * On "Revert": update Room with all old values.
 * On field-level toggle + "Accept changes": a custom mix of old/new values is saved.
 */
class ContactMergeBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ContactMergeBottomSheet"
        private const val ARG_MERGE_EVENT = "merge_event"

        /**
         * Create a new instance pre-loaded with [event].
         * [MergeEvent] is passed as a serialized parcelable-ish approach via
         * individual bundle keys to avoid depending on kotlinx-serialization.
         */
        fun newInstance(event: MergeEvent, onApply: (Map<String, String>) -> Unit): ContactMergeBottomSheet {
            // Store via callback — fragments shouldn't hold closures, but this
            // is a one-shot bottom sheet shown and discarded immediately, so
            // the lifecycle risk is acceptable.
            return ContactMergeBottomSheet().also {
                it.mergeEvent = event
                it.onApplyCallback = onApply
            }
        }
    }

    /** Set by [newInstance] before show(). */
    internal var mergeEvent: MergeEvent? = null
    internal var onApplyCallback: ((Map<String, String>) -> Unit)? = null

    private var _binding: BottomSheetContactMergeBinding? = null
    private val binding get() = _binding!!

    /** Maps fieldName → SwitchMaterial (ON = keep new, OFF = revert to old). */
    private val fieldSwitches = mutableMapOf<String, SwitchMaterial>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetContactMergeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val event = mergeEvent ?: run { dismiss(); return }

        // Header
        val contactName = event.preserved.displayName.ifBlank { getString(R.string.contact_unknown_name) }
        binding.tvMergeSubtitle.text = resources.getQuantityString(
            R.plurals.merge_subtitle,
            event.diffs.size,
            contactName,
            event.diffs.size
        )

        // Build diff rows
        event.diffs.forEach { diff -> addDiffRow(diff) }

        // Accept: apply switches (keep new where ON, revert where OFF)
        binding.btnMergeAccept.setOnClickListener {
            val selections = buildSelections(event)
            onApplyCallback?.invoke(selections)
            dismiss()
        }

        // Revert all: switch every field to old value
        binding.btnMergeRevert.setOnClickListener {
            val allOld = event.diffs.associate { it.field to it.oldValue }
            onApplyCallback?.invoke(allOld)
            dismiss()
        }
    }

    override fun onDestroyView() {
        _binding = null
        fieldSwitches.clear()
        super.onDestroyView()
    }


    private fun addDiffRow(diff: ContactFieldDiff) {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_contact_diff, binding.diffContainer, false)

        row.findViewById<TextView>(R.id.tv_diff_label).text = diff.label
        row.findViewById<TextView>(R.id.tv_diff_old).text = diff.oldValue.ifBlank { "—" }
        row.findViewById<TextView>(R.id.tv_diff_new).text = diff.newValue.ifBlank { "—" }

        val toggle = row.findViewById<SwitchMaterial>(R.id.switch_keep_new).apply {
            isChecked = true  // default: keep the new value
            contentDescription = getString(R.string.merge_field_toggle_desc, diff.label)
        }
        fieldSwitches[diff.field] = toggle

        binding.diffContainer.addView(row)
    }

    private fun buildSelections(event: MergeEvent): Map<String, String> {
        return event.diffs.associate { diff ->
            val keepNew = fieldSwitches[diff.field]?.isChecked ?: true
            diff.field to if (keepNew) diff.newValue else diff.oldValue
        }
    }
}

