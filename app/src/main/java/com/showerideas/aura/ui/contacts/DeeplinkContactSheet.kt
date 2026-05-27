package com.showerideas.aura.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.showerideas.aura.R
import com.showerideas.aura.databinding.BottomSheetDeeplinkContactBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Pre-filled "Add Contact" bottom sheet.
 *
 * Shown when the user opens an AURA share deeplink (https://aura.app/c/…).
 * [DeeplinkUtils.decodeShareUrl] decodes the fields; MainActivity passes them
 * via the navigation bundle key [KEY_DEEPLINK_FIELDS] to [ContactsFragment],
 * which instantiates this sheet via [newInstance].
 *
 * The user can review the pre-filled name/phone/email/company/title before
 * tapping "Save Contact" to persist the card via [ContactsViewModel].
 */
@AndroidEntryPoint
class DeeplinkContactSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "deeplink_contact_sheet"

        /** Bundle key used by MainActivity to pass decoded deeplink fields. */
        const val KEY_DEEPLINK_FIELDS = "deeplink_fields"

        private const val ARG_NAME    = "arg_name"
        private const val ARG_PHONE   = "arg_phone"
        private const val ARG_EMAIL   = "arg_email"
        private const val ARG_COMPANY = "arg_company"
        private const val ARG_TITLE   = "arg_title"

        /**
         * Create an instance from a field map produced by [DeeplinkUtils.decodeShareUrl].
         * Recognised keys: displayName, phone, email, company, title.
         */
        fun newInstance(fields: Map<String, String>) = DeeplinkContactSheet().apply {
            arguments = bundleOf(
                ARG_NAME    to (fields["displayName"] ?: fields["name"] ?: ""),
                ARG_PHONE   to (fields["phone"] ?: ""),
                ARG_EMAIL   to (fields["email"] ?: ""),
                ARG_COMPANY to (fields["company"] ?: ""),
                ARG_TITLE   to (fields["title"] ?: "")
            )
        }
    }

    private var _binding: BottomSheetDeeplinkContactBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContactsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDeeplinkContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefillFields()
        binding.btnDeeplinkDismiss.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnDeeplinkSave.setOnClickListener { saveContact() }
    }

    private fun prefillFields() {
        val args = requireArguments()
        binding.etName.setText(args.getString(ARG_NAME, ""))
        binding.etPhone.setText(args.getString(ARG_PHONE, ""))
        binding.etEmail.setText(args.getString(ARG_EMAIL, ""))
        binding.etCompany.setText(args.getString(ARG_COMPANY, ""))
        binding.etTitle.setText(args.getString(ARG_TITLE, ""))
    }

    private fun saveContact() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        if (name.isBlank()) {
            binding.tilName.error = getString(R.string.deeplink_sheet_name_required)
            return
        }
        binding.tilName.error = null

        val fields = mapOf(
            "displayName" to name,
            "phone"       to (binding.etPhone.text?.toString()?.trim() ?: ""),
            "email"       to (binding.etEmail.text?.toString()?.trim() ?: ""),
            "company"     to (binding.etCompany.text?.toString()?.trim() ?: ""),
            "title"       to (binding.etTitle.text?.toString()?.trim() ?: "")
        ).filterValues { it.isNotBlank() }

        Timber.i("DeeplinkContactSheet: saving contact from deeplink — %s", name)
        viewModel.saveDeeplinkContact(fields)

        Toast.makeText(requireContext(), R.string.deeplink_sheet_saved, Toast.LENGTH_SHORT).show()
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
