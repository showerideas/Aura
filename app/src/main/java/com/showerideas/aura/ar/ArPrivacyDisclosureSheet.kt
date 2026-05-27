package com.showerideas.aura.ar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.showerideas.aura.R
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Task 102 — AR privacy disclosure bottom sheet.
 *
 * Shown exactly once when the user first enables AR Exchange in Settings.
 * Explains that ARCore processes the camera feed to detect faces, that no face
 * data leaves the device, and that the exchange requires the same enrolled gesture
 * as the standard contact exchange.
 *
 * User must explicitly accept before the AR feature activates. The acceptance
 * is persisted to [ArSettingsViewModel.arPrivacyAccepted] and stored in
 * EncryptedSharedPreferences so it is not cleared by normal app data operations.
 *
 * Acceptance is non-revocable via this sheet — users disable AR Exchange through
 * Settings → Privacy → AR Exchange toggle.
 *
 * See: ROADMAP §Task 102
 */
@AndroidEntryPoint
class ArPrivacyDisclosureSheet : BottomSheetDialogFragment() {

    private val viewModel: ArSettingsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_ar_privacy, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvArPrivacyTitle)?.text =
            getString(R.string.ar_privacy_title)
        view.findViewById<TextView>(R.id.tvArPrivacyBody)?.text =
            getString(R.string.ar_privacy_body)

        view.findViewById<Button>(R.id.btnArPrivacyAccept)?.setOnClickListener {
            viewModel.acceptArPrivacy()
            Timber.i("ArPrivacyDisclosureSheet: user accepted AR privacy disclosure")
            dismiss()
        }

        view.findViewById<Button>(R.id.btnArPrivacyDecline)?.setOnClickListener {
            viewModel.declineArPrivacy()
            Timber.i("ArPrivacyDisclosureSheet: user declined AR privacy disclosure")
            dismiss()
        }
    }

    companion object {
        const val TAG = "ArPrivacyDisclosureSheet"
    }
}
