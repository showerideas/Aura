package com.showerideas.aura.data

/**
 * T42 — Deprecated forwarding alias.
 *
 * The canonical DAO has moved to [com.showerideas.aura.data.local.SharePresetDao].
 * This typealias keeps compilation green for any transient references that haven't
 * been migrated yet; it will be deleted in a follow-up cleanup pass.
 */
@Deprecated(
    message = "Use com.showerideas.aura.data.local.SharePresetDao — this stub will be removed.",
    replaceWith = ReplaceWith("SharePresetDao", "com.showerideas.aura.data.local.SharePresetDao")
)
typealias SharePresetDao = com.showerideas.aura.data.local.SharePresetDao
