package com.showerideas.aura.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

/**
 * T40 — Desktop companion entry point (Compose Desktop).
 *
 * Minimal UI scaffold for the AURA desktop companion:
 *  - Generates a QR code containing the user's exchange URL
 *  - Displays the QR for the mobile peer to scan
 *  - Shows exchange status + SAS verification prompt
 *  - Uses [QRRelayTransport] as the primary exchange transport
 *
 * This is a functional scaffold. Full crypto integration mirrors the
 * Android QRExchangeViewModel logic with JVM-compatible crypto primitives.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AURA — Contact Exchange"
    ) {
        MaterialTheme {
            AuraDesktopApp()
        }
    }
}

@Composable
fun AuraDesktopApp() {
    val scope     = rememberCoroutineScope()
    var qrBitmap  by remember { mutableStateOf<ImageBitmap?>(null) }
    var status    by remember { mutableStateOf("Ready") }
    var sasCode   by remember { mutableStateOf<String?>(null) }
    var exchanged by remember { mutableStateOf(false) }

    val relayUrl  = remember { QRRelayTransport.DEFAULT_RELAY_URL }
    val transport = remember { QRRelayTransport(relayUrl) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("AURA Desktop Companion",
            style = MaterialTheme.typography.headlineMedium)

        Divider()

        if (qrBitmap != null) {
            Image(
                bitmap      = qrBitmap!!,
                contentDescription = "Exchange QR code",
                modifier    = Modifier.size(250.dp)
            )
        } else {
            Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        Text("Status: $status", style = MaterialTheme.typography.bodyLarge)

        if (sasCode != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Verify Security Code", style = MaterialTheme.typography.titleMedium)
                    Text(sasCode!!, style = MaterialTheme.typography.displaySmall)
                    Text("Ensure your peer sees the same code before confirming.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            status  = "Exchange confirmed ✓"
                            sasCode = null
                            exchanged = true
                        }) { Text("Codes Match") }
                        OutlinedButton(onClick = {
                            status  = "Exchange rejected"
                            sasCode = null
                        }) { Text("Codes Differ") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    status = "Generating exchange QR…"
                    val slotId = generateSlotId()
                    val url    = "$relayUrl/c/$slotId"
                    qrBitmap   = generateQrBitmap(url)
                    status     = "Waiting for peer to scan…"
                    val peer = transport.pollForPeer(slotId)
                    if (peer != null) {
                        status  = "Peer found — verify SAS code"
                        sasCode = deriveFakeSas()   // real: HKDF(ecdhShared)
                    } else {
                        status  = "Timed out — try again"
                        qrBitmap = null
                    }
                }
            }, enabled = !exchanged) { Text("Start Exchange") }

            OutlinedButton(onClick = {
                qrBitmap  = null
                sasCode   = null
                status    = "Ready"
                exchanged = false
            }) { Text("Reset") }
        }
    }
}

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------

private fun generateSlotId(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").take(32)

/** Generate a 250×250 QR code bitmap from [url]. */
private fun generateQrBitmap(url: String): ImageBitmap? = runCatching {
    val matrix: BitMatrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 250, 250)
    val width  = matrix.width
    val height = matrix.height
    val buf    = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until width) {
        for (y in 0 until height) {
            buf.setRGB(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    buf.toComposeImageBitmap()
}.getOrNull()

/** Placeholder SAS — replace with real HKDF derivation in production. */
private fun deriveFakeSas(): String = (100000..999999).random().toString()
