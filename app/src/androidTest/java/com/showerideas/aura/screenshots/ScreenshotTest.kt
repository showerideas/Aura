package com.showerideas.aura.screenshots

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.locale.LocaleTestRule

/**
 * Locale screenshot automation for all supported locales.
 *
 * These tests are run via `./scripts/screengrab_locales.sh` (backed by the
 * fastlane Screengrab gem). They are NOT run as part of the standard CI
 * `./gradlew connectedAndroidTest` — they require a real device or emulator
 * with Developer Options → Disable animations enabled.
 *
 * Supported locales
 * de, es, fr, hi, ja, ko, zh-CN, en-US
 *
 * Output
 * fastlane/metadata/android/<locale>/images/phoneScreenshots/
 *
 * Running manually
 * 1. Connect a device / start emulator
 * 2. `./scripts/screengrab_locales.sh`
 * 3. Review screenshots and update locale strings as needed
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @Rule
    @JvmField
    val localeTestRule = LocaleTestRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun screengrab_home_screen() {
        val intent = Intent(context, com.showerideas.aura.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ActivityScenario.launch<com.showerideas.aura.ui.MainActivity>(intent).use {
            // Brief stabilisation before capture
            Thread.sleep(1000)
            Screengrab.screenshot("01_home")
        }
    }

    @Test
    fun screengrab_settings_screen() {
        val intent = Intent(context, com.showerideas.aura.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            action = "com.showerideas.aura.OPEN_SETTINGS"
        }
        ActivityScenario.launch<com.showerideas.aura.ui.MainActivity>(intent).use {
            Thread.sleep(800)
            Screengrab.screenshot("02_settings")
        }
    }
}
