package com.showerideas.aura.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.showerideas.aura.R

/**
 * Bottom sheet shown when one or more runtime permissions required by
 * AURA's nearby exchange are denied. Each denied permission gets a row
 * with an icon, a human-readable label, and a one-line rationale. The
 * primary action deep-links to the app's settings page so the user can
 * grant permissions; "Not now" dismisses and finishes the host activity
 * because the app cannot work in a permission-denied state.
 */
class PermissionRationaleBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_DENIED = "denied_permissions"
        const val TAG = "perm_rationale"

        fun newInstance(denied: List<String>): PermissionRationaleBottomSheet =
            PermissionRationaleBottomSheet().apply {
                arguments = bundleOf(ARG_DENIED to ArrayList(denied))
            }

        /**
         * Map a permission constant to (label, rationale). Anything we don't
         * have a hand-written rationale for falls back to the bare permission
         * name so the UI still renders something useful.
         */
        private data class PermInfo(val labelRes: Int, val rationaleRes: Int)

        private fun infoFor(permission: String): PermInfo = when (permission) {
            Manifest.permission.BLUETOOTH_SCAN -> PermInfo(
                R.string.perm_label_bluetooth_scan,
                R.string.perm_reason_bluetooth_scan
            )
            Manifest.permission.BLUETOOTH_ADVERTISE -> PermInfo(
                R.string.perm_label_bluetooth_advertise,
                R.string.perm_reason_bluetooth_advertise
            )
            Manifest.permission.BLUETOOTH_CONNECT -> PermInfo(
                R.string.perm_label_bluetooth_connect,
                R.string.perm_reason_bluetooth_connect
            )
            Manifest.permission.ACCESS_FINE_LOCATION -> PermInfo(
                R.string.perm_label_location,
                R.string.perm_reason_location
            )
            Manifest.permission.NEARBY_WIFI_DEVICES -> PermInfo(
                R.string.perm_label_nearby_wifi,
                R.string.perm_reason_nearby_wifi
            )
            Manifest.permission.POST_NOTIFICATIONS -> PermInfo(
                R.string.perm_label_notifications,
                R.string.perm_reason_notifications
            )
            else -> PermInfo(
                R.string.perm_label_generic,
                R.string.perm_reason_generic
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_permission_rationale, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("UNCHECKED_CAST")
        val denied: List<String> = arguments?.getStringArrayList(ARG_DENIED)?.toList() ?: emptyList()

        val rowContainer = view.findViewById<LinearLayout>(R.id.container_permission_rows)
        denied.forEach { perm -> rowContainer.addView(buildRow(perm)) }

        view.findViewById<View>(R.id.btn_open_settings).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            dismiss()
        }
        view.findViewById<View>(R.id.btn_not_now).setOnClickListener {
            dismiss()
            // The app cannot function without these permissions — finish the
            // activity so the user isn't left in a broken state.
            requireActivity().finish()
        }

        // Disable cancel-on-dismiss-outside so the user must make a choice.
        isCancelable = false
    }

    private fun buildRow(permission: String): View {
        val info = infoFor(permission)
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val v = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, v, 0, v)
        }
        val label = TextView(ctx).apply {
            text = getString(info.labelRes)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val rationale = TextView(ctx).apply {
            text = getString(info.rationaleRes)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_secondary))
            textSize = 13f
        }
        row.addView(label)
        row.addView(rationale)
        return row
    }
}
