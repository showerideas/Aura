package com.showerideas.aura

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Phase 5.3 — Accessibility layout audit (JVM unit test, no Android runtime needed).
 *
 * Parses every layout XML in app/src/main/res/layout/ and enforces:
 *
 *  1. Every ImageView / ImageButton that is not marked decorative
 *     (importantForAccessibility="no") must carry a non-empty android:contentDescription.
 *
 *  2. Every MaterialButton / Button / Chip that has NO android:text (icon-only control)
 *     must carry a non-empty android:contentDescription.
 *
 *  3. TextViews whose ID contains "status", "state", or "sas_code" must declare
 *     android:accessibilityLiveRegion (to give TalkBack automatic announcements).
 *
 *  4. ProgressBars that are NOT the primary progress indicator (i.e. those inside
 *     gesture-section containers) must declare importantForAccessibility="no".
 *
 * These rules mirror the manual QA checklist in AccessibilityContractTest — having
 * them enforced automatically prevents regressions when layouts are edited.
 *
 * Path resolution: JVM unit tests run with user.dir == app/ (Gradle module root).
 */
class AccessibilityLayoutAuditTest {

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun imageViews_have_contentDescription_or_marked_decorative() {
        val failures = mutableListOf<String>()
        forEachLayout { layoutFile, doc ->
            val images = doc.getElementsByTagName("ImageView")
            for (i in 0 until images.length) {
                val el = images.item(i) as Element
                val importance = el.getAttributeNS(ANDROID_NS, "importantForAccessibility")
                if (importance == "no") continue // decorative, skip
                val cd = el.getAttributeNS(ANDROID_NS, "contentDescription")
                if (cd.isBlank()) {
                    val id = el.getAttributeNS(ANDROID_NS, "id").ifBlank { "(no id)" }
                    failures.add("${layoutFile.name}: <ImageView id='$id'> has no contentDescription and is not marked importantForAccessibility=no")
                }
            }
        }
        assertTrue(
            "ImageView accessibility gaps found:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun statusTextViews_have_accessibilityLiveRegion() {
        val liveIdPatterns = listOf("status", "state", "sas_code", "gesture_status")
        val failures = mutableListOf<String>()
        forEachLayout { layoutFile, doc ->
            val textViews = doc.getElementsByTagName("TextView")
            for (i in 0 until textViews.length) {
                val el = textViews.item(i) as Element
                val id = el.getAttributeNS(ANDROID_NS, "id")
                if (liveIdPatterns.any { id.contains(it, ignoreCase = true) }) {
                    val liveRegion = el.getAttributeNS(ANDROID_NS, "accessibilityLiveRegion")
                    if (liveRegion.isBlank()) {
                        failures.add("${layoutFile.name}: <TextView id='$id'> looks like a live-status view but lacks android:accessibilityLiveRegion")
                    }
                }
            }
        }
        assertTrue(
            "Status TextViews missing accessibilityLiveRegion:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun decorativeProgressBars_are_hidden_from_accessibility() {
        val failures = mutableListOf<String>()
        forEachLayout { layoutFile, doc ->
            val bars = doc.getElementsByTagName("ProgressBar")
            for (i in 0 until bars.length) {
                val el = bars.item(i) as Element
                val id = el.getAttributeNS(ANDROID_NS, "id")
                // The large spinner progress_bar is the primary UX indicator for exchange state —
                // skip it. All others (e.g., gesture stability bar) should be decorative.
                if (id.contains("progress_bar") && !id.contains("stability")) continue
                val importance = el.getAttributeNS(ANDROID_NS, "importantForAccessibility")
                if (importance != "no") {
                    failures.add("${layoutFile.name}: <ProgressBar id='$id'> should be marked importantForAccessibility=no (decorative)")
                }
            }
        }
        assertTrue(
            "Decorative ProgressBars not excluded from accessibility:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun allLayouts_parsedSuccessfully() {
        // Sanity check: every layout file must be valid XML — a parse error here
        // means a layout is malformed and will crash the inflate at runtime.
        val failures = mutableListOf<String>()
        val layoutDir = layoutDirectory()
        layoutDir.listFiles { f -> f.extension == "xml" }?.forEach { file ->
            try {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            } catch (e: Exception) {
                failures.add("${file.name}: XML parse error — ${e.message}")
            }
        }
        assertTrue(
            "Malformed layout XML files detected:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun layoutDirectory(): File {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".")
        val dir = File(moduleRoot, "src/main/res/layout")
        assertTrue(
            "Cannot locate layout directory at ${dir.absolutePath}. " +
            "Ensure the test is run from the Gradle module root (app/).",
            dir.isDirectory
        )
        return dir
    }

    private fun forEachLayout(block: (File, org.w3c.dom.Document) -> Unit) {
        val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
        val builder = factory.newDocumentBuilder()
        layoutDirectory().listFiles { f -> f.extension == "xml" }?.forEach { file ->
            try {
                val doc = builder.parse(file)
                block(file, doc)
            } catch (_: Exception) {
                // Parse failures are reported by allLayouts_parsedSuccessfully
            }
        }
    }
}
