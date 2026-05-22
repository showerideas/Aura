package com.showerideas.aura.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

/**
 * Writes [vcardString] to a temp file under `cacheDir/vcards/` and launches
 * a system share sheet using a [FileProvider] URI. MIME type is set to
 * `text/vcard` so address-book apps surface as targets.
 *
 * The authority must match the one declared in `AndroidManifest.xml`.
 */
private const val FILE_PROVIDER_AUTHORITY = "com.showerideas.aura.fileprovider"

fun Context.shareVCard(vcardString: String, filename: String) {
    val safeName = filename
        .ifBlank { "contact" }
        .let { if (it.endsWith(".vcf", ignoreCase = true)) it else "$it.vcf" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")

    val dir = File(cacheDir, "vcards").apply { mkdirs() }
    val file = File(dir, safeName).apply { writeText(vcardString, Charsets.UTF_8) }

    val uri: Uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
    Timber.d("Sharing vCard: $uri (${file.length()} bytes)")

    val intent = ShareCompat.IntentBuilder(this)
        .setType("text/vcard")
        .setStream(uri)
        .intent
        .apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    val chooser = Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
}
