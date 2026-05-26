package com.showerideas.aura.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.showerideas.aura.R
import com.showerideas.aura.ui.MainActivity
import timber.log.Timber

/**
 * Task 45 — Dynamic launcher shortcut management.
 *
 * Pushes a "Exchange with [last contact name]" dynamic shortcut after each
 * successful exchange. Limit: max 4 shortcuts total (2 static + this dynamic
 * + 1 reserved). [ShortcutManagerCompat.pushDynamicShortcut] replaces the
 * previous "last_exchange" shortcut automatically.
 *
 * See: [developer.android.com/develop/ui/compose/system/shortcuts]
 */
object AuraShortcutManager {

    private const val SHORTCUT_ID_LAST_EXCHANGE = "last_exchange"
    private const val SHORTCUT_RANK_LAST = 0  // Highest rank = shown first

    /**
     * Push (or update) the "Exchange with [contactName]" dynamic shortcut.
     *
     * Call from [NearbyExchangeService] after a successful card exchange.
     * [ShortcutManagerCompat.pushDynamicShortcut] handles the replacement
     * if the slot is already occupied.
     *
     * @param context Application context.
     * @param contactName Display name of the last exchanged contact.
     * @param contactId   Stable ID passed as intent extra for deeplink routing.
     */
    fun pushLastExchangeShortcut(context: Context, contactName: String, contactId: String) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Timber.d("ShortcutManager: pin not supported on this launcher — skipping")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.showerideas.aura.ACTION_VIEW_CONTACT"
            putExtra("contact_id", contactId)
            // Required: intents in shortcuts must have a non-MAIN action and no category
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID_LAST_EXCHANGE)
            .setShortLabel(context.getString(R.string.shortcut_last_exchange_short, contactName))
            .setLongLabel(context.getString(R.string.shortcut_last_exchange_long, contactName))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher))
            .setIntent(intent)
            .setRank(SHORTCUT_RANK_LAST)
            .build()

        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            Timber.i("ShortcutManager: pushed last_exchange shortcut — contact='$contactName'")
        }.onFailure { e ->
            Timber.w(e, "ShortcutManager: failed to push dynamic shortcut")
        }
    }

    /** Remove the last_exchange dynamic shortcut (e.g., on contact deletion). */
    fun removeLastExchangeShortcut(context: Context) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHORTCUT_ID_LAST_EXCHANGE))
    }
}
