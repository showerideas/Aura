package com.showerideas.aura

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM unit test that verifies localization key coverage without needing an Android device.
 *
 * Why this exists
 * AURA ships 7 locale overlays (DE, ES, FR, HI, JA, KO, ZH-CN). Any time a new string
 * is added to `values/strings.xml` there is a risk of forgetting to add translations,
 * which causes a silent English fallback for users in those locales. This test catches
 * that regression at build time (before any device is involved).
 *
 * How it works
 * The test reads `strings.xml` using the Java XML DOM parser (no Android runtime needed).
 * It collects every `<string name="...">` key from the base `values/` folder and from
 * each `values-xx/` overlay, then asserts that the overlay sets are supersets of the base.
 *
 * `translatable="false"` strings are excluded — they are intentionally left untranslated
 * (e.g. app_name, package identifiers).
 *
 * Path resolution
 * JVM unit tests run with the working directory set to the Gradle module root (`app/`).
 * The path `src/main/res` is therefore resolvable from `System.getProperty("user.dir")`.
 *
 * Covers ROADMAP.md Phase 2 ("Add a CI lint step that fails if any string key exists
 * in `values/` but is missing in any `values-xx/`").
 */
class LocalizationCoverageTest {

    companion object {
        private val LOCALES = listOf("de", "es", "fr", "hi", "ja", "ko", "zh-rCN")
    }

    // Tests

    @Test
    fun all_base_string_keys_are_present_in_every_locale() {
        val resDir = resDirectory()
        val baseKeys = readTranslatableKeys(File(resDir, "values/strings.xml"))

        assertTrue(
            "Base strings.xml must contain at least one translatable string — " +
            "check the file path: ${File(resDir, "values/strings.xml").absolutePath}",
            baseKeys.isNotEmpty()
        )

        val failures = mutableListOf<String>()
        for (locale in LOCALES) {
            val overlayFile = File(resDir, "values-$locale/strings.xml")
            if (!overlayFile.exists()) {
                failures.add("Missing locale overlay: $overlayFile")
                continue
            }
            val overlayKeys = readTranslatableKeys(overlayFile)
            val missing = baseKeys - overlayKeys
            if (missing.isNotEmpty()) {
                failures.add(
                    "Locale '$locale' is missing ${missing.size} key(s): " +
                    missing.sorted().joinToString(", ")
                )
            }
        }

        assertTrue(
            "Localization gaps detected:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun no_locale_contains_keys_absent_from_base() {
        // Inverse check: catch "orphan" keys in overlays that were removed from base.
        // These are harmless at runtime but indicate stale translation work.
        val resDir = resDirectory()
        val baseKeys = readTranslatableKeys(File(resDir, "values/strings.xml"))

        val warnings = mutableListOf<String>()
        for (locale in LOCALES) {
            val overlayFile = File(resDir, "values-$locale/strings.xml")
            if (!overlayFile.exists()) continue
            val overlayKeys = readTranslatableKeys(overlayFile)
            val orphans = overlayKeys - baseKeys
            if (orphans.isNotEmpty()) {
                warnings.add(
                    "Locale '$locale' has ${orphans.size} orphan key(s) not in base: " +
                    orphans.sorted().joinToString(", ")
                )
            }
        }

        assertTrue(
            "Orphan translation keys detected (stale — remove from overlay files):\n" +
            warnings.joinToString("\n"),
            warnings.isEmpty()
        )
    }

    @Test
    fun every_locale_directory_exists() {
        val resDir = resDirectory()
        val missing = LOCALES.filter { !File(resDir, "values-$it").isDirectory }
        assertTrue(
            "Missing locale resource directories: $missing",
            missing.isEmpty()
        )
    }

    // Helpers

    /**
     * Resolve the `app/src/main/res` directory.
     * JVM unit tests run with `user.dir` == the Gradle module root (`app/`).
     */
    private fun resDirectory(): File {
        val moduleRoot = File(System.getProperty("user.dir") ?: ".")
        val resDir = File(moduleRoot, "src/main/res")
        assertTrue(
            "Cannot locate res directory at ${resDir.absolutePath}. " +
            "Ensure the test is run from the Gradle module root.",
            resDir.isDirectory
        )
        return resDir
    }

    /**
     * Parse [file] and return a set of `name` attribute values for every
     * `<string>` element that does NOT have `translatable="false"`.
     */
    private fun readTranslatableKeys(file: File): Set<String> {
        if (!file.exists()) return emptySet()

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as org.w3c.dom.Element
            val translatable = element.getAttribute("translatable")
            if (translatable.equals("false", ignoreCase = true)) continue
            val name = element.getAttribute("name")
            if (name.isNotBlank()) keys.add(name)
        }
        return keys
    }
}
