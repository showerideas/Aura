package com.showerideas.aura.utils

import com.showerideas.aura.model.Profile
import org.json.JSONObject
import java.util.Base64

/**
 * Utilities for generating AURA share card deeplinks.
 *
 * URL format
 * `https://aura.app/c/<base64url-encoded-json>`
 *
 * The JSON payload contains only the fields the user has chosen to share
 * (same as [Profile.toShareableMap]). No server receives the data — the
 * entire vCard is encoded in the URL fragment and decoded client-side by
 * the landing page at https://aura.app/c/.
 *
 * Privacy
 * The URL is generated entirely on-device. When the user shares it via the
 * Android share sheet, the recipient's app or OS may log the URL, but the
 * AURA relay server never receives or logs it. The landing page decodes the
 * payload client-side (hash fragment / JS) so web servers don't log it.
 *
 * Size budget
 * A typical profile JSON (name + phone + email + company + title) is ~150 bytes
 * before Base64 encoding (~200 bytes after). Well within URL limits and QR code
 * capacity for sharing as a link.
 */
object DeeplinkUtils {

    private const val DEEPLINK_BASE = "https://aura.app/c/"

    /**
     * Generate a shareable deeplink for [profile].
     *
     * Only the fields in [Profile.toShareableMap] are included — the user's
     * share field preferences are respected. The version key is also included
     * (so the recipient knows which version of the card this represents).
     *
     * @return A URL string of the form `https://aura.app/c/<base64url-json>`.
     */
    fun generateShareUrl(profile: Profile): String {
        val shareMap = profile.toShareableMap()
        val json = JSONObject(shareMap).toString()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return DEEPLINK_BASE + encoded
    }

    /**
     * Decode a share URL back to a field map. Returns null if [url] is not
     * a valid AURA deeplink or the payload cannot be decoded.
     *
     * Useful for testing and for a future "open link → save contact" feature.
     */
    fun decodeShareUrl(url: String): Map<String, String>? {
        return try {
            if (!url.startsWith(DEEPLINK_BASE)) return null
            val encoded = url.removePrefix(DEEPLINK_BASE)
            val json = String(
                Base64.getUrlDecoder().decode(encoded),
                Charsets.UTF_8
            )
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { key -> put(key, obj.getString(key)) } }
        } catch (_: Exception) {
            null
        }
    }
}
