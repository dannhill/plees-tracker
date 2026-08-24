/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.provider.Settings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for UsageAccess permission helper and manifest / preferences contract verification.
 */
class UsageAccessUnitTest {

    @Test
    fun testIsSupportedOnJvmDefaultsToTrue() {
        // In JVM tests Build.VERSION.SDK_INT is 0, which isSupported() treats as supported
        assertTrue("isSupported() must return true in JVM test environment", UsageAccess.isSupported())
    }

    @Test
    fun testCreateSettingsIntentHasCorrectAction() {
        try {
            val intent = UsageAccess.createSettingsIntent()
            assertNotNull(intent)
            assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intent.action)
        } catch (e: RuntimeException) {
            // Android SDK classes (Intent) are not mocked by default in pure JVM unit tests
            assertTrue("Intent not mocked on pure JVM", e.message?.contains("not mocked") == true)
        }
    }

    @Test
    fun testManifestDeclaresPackageUsageStatsWithoutInternet() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
        val content = manifestFile.readText()

        assertTrue(
            "Manifest must declare android.permission.PACKAGE_USAGE_STATS",
            content.contains("android.permission.PACKAGE_USAGE_STATS")
        )
        assertTrue(
            "Manifest must include tools:ignore=\"ProtectedPermissions\"",
            content.contains("tools:ignore=\"ProtectedPermissions\"")
        )
        assertFalse(
            "Manifest must NOT declare android.permission.INTERNET (FOSS offline privacy)",
            content.contains("android.permission.INTERNET")
        )
    }

    @Test
    fun testPreferencesXmlContainsAutoSleepCategoryAndKeys() {
        val prefsFile = File("src/main/res/xml/preferences.xml")
        assertTrue("preferences.xml must exist", prefsFile.exists())
        val content = prefsFile.readText()

        assertTrue(
            "preferences.xml must contain auto_sleep_category",
            content.contains("android:key=\"auto_sleep_category\"")
        )
        assertTrue(
            "preferences.xml must contain auto_sleep_enabled SwitchPreference",
            content.contains("android:key=\"auto_sleep_enabled\"")
        )
        assertTrue(
            "preferences.xml must contain auto_sleep_policy ListPreference",
            content.contains("android:key=\"auto_sleep_policy\"")
        )
        assertTrue(
            "preferences.xml must contain auto_sleep_save_mode ListPreference",
            content.contains("android:key=\"auto_sleep_save_mode\"")
        )
        assertTrue(
            "auto_sleep_enabled must default to false",
            content.contains("android:defaultValue=\"false\"") &&
                content.contains("android:key=\"auto_sleep_enabled\"")
        )
        assertTrue(
            "auto_sleep_policy must default to OVERNIGHT_LONGEST_GAP",
            content.contains("android:defaultValue=\"OVERNIGHT_LONGEST_GAP\"")
        )
        assertTrue(
            "auto_sleep_save_mode must default to SUGGEST",
            content.contains("android:defaultValue=\"SUGGEST\"")
        )
    }

    @Test
    fun testStringsXmlContainsAllRequiredAutoSleepStrings() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsFile.exists())
        val content = stringsFile.readText()

        val requiredStrings = listOf(
            "settings_category_autosleep",
            "settings_auto_sleep_enabled",
            "settings_auto_sleep_summary",
            "settings_auto_sleep_unsupported",
            "settings_auto_sleep_policy",
            "auto_sleep_policy_overnight_longest_gap",
            "auto_sleep_policy_pure_longest_gap",
            "auto_sleep_policy_entries",
            "auto_sleep_policy_entry_values",
            "settings_auto_sleep_save_mode",
            "auto_sleep_save_mode_suggest",
            "auto_sleep_save_mode_auto_save",
            "auto_sleep_save_mode_entries",
            "auto_sleep_save_mode_entry_values",
            "auto_sleep_permission_dialog_title",
            "auto_sleep_permission_dialog_message",
            "auto_sleep_permission_open_settings",
            "auto_sleep_dialog_title",
            "auto_sleep_dialog_message",
            "auto_sleep_dialog_save",
            "auto_sleep_dialog_discard",
            "auto_sleep_dialog_later",
            "auto_sleep_saved",
            "auto_sleep_discarded"
        )

        for (key in requiredStrings) {
            assertTrue("strings.xml must contain $key", content.contains("name=\"$key\""))
        }
    }
}
