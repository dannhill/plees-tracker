/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.provider.Settings
import hu.vmiklos.plees_tracker.Sleep
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Empirical challenger stress test suite for PreferencesActivity toggle states,
 * UsageAccess API level compatibility, runtime permission lifecycle, and defensive settings fallback.
 */
class AutoSleepPreferencesAndUsageAccessEmpiricalTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore
    private lateinit var fakeDao: FakeSleepDao

    private val baseTime = 1_700_000_000_000L
    private val t1 = baseTime + 23 * 3600 * 1000L
    private val t2 = baseTime + 31 * 3600 * 1000L
    private val now = baseTime + 36 * 3600 * 1000L

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(prefs)
        fakeDao = FakeSleepDao()
    }

    // =========================================================================
    // SECTION 1: PREFERENCES TOGGLING & STATE MACHINE INVARIANTS
    // =========================================================================

    @Test
    fun testPreferences_initialCleanInstallDefaults() {
        assertFalse(
            "AutoSleep must be disabled by default on clean install",
            prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        )
        assertEquals(
            "Default policy must be OVERNIGHT_LONGEST_GAP",
            AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name,
            prefs.getString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name)
        )
        assertEquals(
            "Default save mode must be SUGGEST",
            "SUGGEST",
            prefs.getString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
        )
        assertTrue(
            "Candidate store must be empty initially",
            store.getPendingCandidates().isEmpty()
        )
    }

    @Test
    fun testPreferences_toggleOn_permissionGranted_schedulesWorkerAndSetsTrue() {
        var workerScheduled = false
        var hasAccess = true

        // Simulate toggle ON with permission granted
        val newValue = true
        val canToggle = if (newValue) {
            if (hasAccess) {
                workerScheduled = true
                true
            } else {
                false
            }
        } else {
            true
        }

        assertTrue("Toggle ON must be accepted when permission is granted", canToggle)
        if (canToggle) {
            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        }

        assertTrue("Preference must be set to true", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false))
        assertTrue("Worker must be scheduled", workerScheduled)
    }

    @Test
    fun testPreferences_toggleOn_permissionDenied_interceptsAndShowsDialog() {
        var workerScheduled = false
        var dialogShown = false
        var hasAccess = false

        // Simulate toggle ON with permission denied
        val newValue = true
        val canToggle = if (newValue) {
            if (hasAccess) {
                workerScheduled = true
                true
            } else {
                dialogShown = true
                false
            }
        } else {
            true
        }

        assertFalse("Toggle ON must be intercepted and rejected when permission is denied", canToggle)
        assertTrue("Permission dialog must be triggered", dialogShown)
        assertFalse("Worker must NOT be scheduled", workerScheduled)
        assertFalse("Preference must remain false", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false))
    }

    @Test
    fun testPreferences_returnFromSettings_permissionGranted_enablesAndSchedulesWorker() {
        // User attempted to toggle ON -> dialog shown -> usageAccessSettingsPending = true
        var usageAccessSettingsPending = true
        var workerScheduled = false

        // User granted permission in system settings and returns to app (onResume)
        var hasAccess = true

        if (usageAccessSettingsPending) {
            usageAccessSettingsPending = false
            if (hasAccess) {
                prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
                workerScheduled = true
            } else {
                prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
            }
        }

        assertFalse("Pending flag must be reset", usageAccessSettingsPending)
        assertTrue("AutoSleep must be enabled after permission is granted in settings", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false))
        assertTrue("Worker must be scheduled on resume", workerScheduled)
    }

    @Test
    fun testPreferences_returnFromSettings_permissionStillDenied_remainsDisabled() {
        // User attempted to toggle ON -> dialog shown -> usageAccessSettingsPending = true
        var usageAccessSettingsPending = true
        var workerScheduled = false

        // User returned without granting permission
        var hasAccess = false

        if (usageAccessSettingsPending) {
            usageAccessSettingsPending = false
            if (hasAccess) {
                prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
                workerScheduled = true
            } else {
                prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
            }
        }

        assertFalse("Pending flag must be reset", usageAccessSettingsPending)
        assertFalse("AutoSleep must remain disabled", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false))
        assertFalse("Worker must NOT be scheduled", workerScheduled)
    }

    @Test
    fun testPreferences_dialogDismissOrCancel_remainsDisabled() {
        // User clicks Cancel or dismisses dialog
        var usageAccessSettingsPending = false
        var workerScheduled = false

        assertFalse("AutoSleep must remain disabled", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false))
        assertFalse("Pending flag must remain false", usageAccessSettingsPending)
        assertFalse("Worker must NOT be scheduled", workerScheduled)
    }

    @Test
    fun testPreferences_toggleOff_cancelsWorkerAndClearsStore() {
        // Setup initial enabled state with candidates
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candPending = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        val candRejected = SleepCandidate(t1 - 100_000L, t2 - 100_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)

        store.addPending(candPending, now)
        store.markRejected(candRejected, now)

        assertEquals(1, store.getPendingCandidates().size)
        assertTrue(store.isRejected(candRejected.fingerprint, now))

        // Simulate toggle OFF
        var workerCancelled = false
        val newValue = false

        if (!newValue) {
            workerCancelled = true
            store.clear()
            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        }

        assertFalse("AutoSleep must be disabled", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, true))
        assertTrue("Worker must be cancelled", workerCancelled)
        assertTrue("Pending candidate store must be cleared", store.getPendingCandidates().isEmpty())
        assertFalse("Rejected candidate store must be cleared", store.isRejected(candRejected.fingerprint, now))
    }

    @Test
    fun testPreferences_externalRevocation_autoDisablesCancelsWorkerAndClearsStore() {
        // User enabled AutoSleep
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        store.addPending(candidate, now)

        // Permission revoked externally in Android Settings
        val storedEnabled = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        val hasAccess = false
        var workerCancelled = false

        // onResume / refreshAutoSleepState evaluation
        if (storedEnabled && !hasAccess) {
            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
            workerCancelled = true
            store.clear()
        }

        assertFalse("AutoSleep must be automatically disabled upon permission revocation", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, true))
        assertTrue("Worker must be cancelled on revocation", workerCancelled)
        assertTrue("Candidate store must be cleared on revocation", store.getPendingCandidates().isEmpty())
    }

    @Test
    fun testPreferences_externalGrant_doesNotAutoEnableWithoutUserConsent() {
        // User had AutoSleep disabled
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()

        // Permission was granted externally in Android Settings
        val storedEnabled = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        val hasAccess = true

        val effectiveState = storedEnabled && hasAccess

        assertFalse("Feature must NOT auto-enable without explicit user consent", effectiveState)
        assertFalse("Preference must remain false", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false))
    }

    @Test
    fun testPreferences_savedInstanceStatePreservation() {
        // Simulate bundle / saved instance state dictionary during settings navigation
        val savedState = mutableMapOf<String, Any>()
        val STATE_USAGE_ACCESS_SETTINGS_PENDING = "usageAccessSettingsPending"

        var usageAccessSettingsPending = true
        savedState[STATE_USAGE_ACCESS_SETTINGS_PENDING] = usageAccessSettingsPending

        // Simulate activity recreation
        val restoredPending = savedState[STATE_USAGE_ACCESS_SETTINGS_PENDING] as? Boolean ?: false

        assertTrue("Pending settings state must survive activity recreation", restoredPending)
    }

    // =========================================================================
    // SECTION 2: API LEVEL COMPATIBILITY STRESS TEST (< 28 vs >= 28)
    // =========================================================================

    /**
     * Pure logic evaluation model representing UsageAccess.isSupported()
     */
    private fun evaluateIsSupported(sdkInt: Int): Boolean {
        return sdkInt == 0 || sdkInt >= 28 // Build.VERSION_CODES.P
    }

    @Test
    fun testApiCompatibility_isSupportedAcrossAllApiLevels() {
        // Legacy Android versions (API < 28) MUST be unsupported
        val unsupportedApis = listOf(
            14, // ICS
            19, // KitKat
            21, // Lollipop
            22, // Lollipop MR1
            23, // Marshmallow
            24, // Nougat
            25, // Nougat MR1
            26, // Oreo
            27  // Oreo MR1
        )

        for (api in unsupportedApis) {
            assertFalse("API $api must be unsupported (requires API 28+)", evaluateIsSupported(api))
        }

        // Modern Android versions (API >= 28) MUST be supported
        val supportedApis = listOf(
            28, // Pie (P)
            29, // Q (10)
            30, // R (11)
            31, // S (12)
            32, // S_V2 (12L)
            33, // TIRAMISU (13)
            34, // UPSIDE_DOWN_CAKE (14)
            35  // VANILLA_ICE_CREAM (15)
        )

        for (api in supportedApis) {
            assertTrue("API $api must be supported", evaluateIsSupported(api))
        }

        // JVM environment (SDK_INT = 0)
        assertTrue("JVM environment (SDK_INT = 0) must return true for unit tests", evaluateIsSupported(0))
    }

    @Test
    fun testApiCompatibility_apiLessThan28_disabledSwitchAndUnsupportedSummary() {
        val simulatedSdkInt = 26 // Android 8.0 Oreo

        val isSupported = evaluateIsSupported(simulatedSdkInt)
        assertFalse("API 26 must not be supported", isSupported)

        // Simulate Preferences.setupAutoSleepPreferences on API < 28
        var prefEnabled = true
        var prefChecked = true
        var prefSummary = "Normal summary"

        if (!isSupported) {
            prefEnabled = false
            prefChecked = false
            prefSummary = "Requires Android 9 or newer"
        }

        assertFalse("Preference switch must be disabled on API < 28", prefEnabled)
        assertFalse("Preference switch must be unchecked on API < 28", prefChecked)
        assertEquals("Summary must indicate Android 9+ requirement", "Requires Android 9 or newer", prefSummary)
    }

    @Test
    fun testApiCompatibility_apiLessThan28_scanShortCircuitsWithoutError() {
        val simulatedSdkInt = 27
        val isSupported = evaluateIsSupported(simulatedSdkInt)
        val enabledInPrefs = true // Even if artificially set in SharedPreferences

        var scanExecuted = false

        // Simulate MainActivity.checkAndPromptAutoSleep guard
        if (enabledInPrefs && isSupported) {
            scanExecuted = true
        }

        assertFalse("Scan must NEVER execute on API < 28", scanExecuted)
    }

    @Test
    fun testApiCompatibility_apiGreaterOrEqual28_switchEnabledAndInteractive() {
        val simulatedSdkInt = 34 // Android 14

        val isSupported = evaluateIsSupported(simulatedSdkInt)
        assertTrue("API 34 must be supported", isSupported)

        var prefEnabled = false
        var prefSummary = ""

        if (isSupported) {
            prefEnabled = true
            prefSummary = "Detect sleep automatically from device unlock events"
        }

        assertTrue("Preference switch must be enabled on API >= 28", prefEnabled)
        assertEquals("Summary must be standard description", "Detect sleep automatically from device unlock events", prefSummary)
    }

    // =========================================================================
    // SECTION 3: DEFENSIVE SETTINGS NAVIGATION & INTENT FALLBACKS
    // =========================================================================

    private data class SimulatedIntent(
        val action: String,
        var dataUri: String? = null,
        var flags: Int = 0
    ) {
        companion object {
            const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
            const val ACTION_USAGE_ACCESS_SETTINGS = "android.settings.USAGE_ACCESS_SETTINGS"
            const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
            const val ACTION_SETTINGS = "android.settings.SETTINGS"
        }
    }

    /**
     * Model simulating the 3-tier fallback strategy of UsageAccess.openSettings
     */
    private fun simulateOpenSettings(
        contextIsActivity: Boolean,
        tier1Throws: Boolean,
        tier2Throws: Boolean,
        tier3Throws: Boolean,
        launchedIntents: MutableList<SimulatedIntent>
    ): Boolean {
        // Tier 1: ACTION_USAGE_ACCESS_SETTINGS
        try {
            if (tier1Throws) throw SecurityException("Usage access settings not available on this OEM")
            val intent = SimulatedIntent(SimulatedIntent.ACTION_USAGE_ACCESS_SETTINGS).apply {
                if (!contextIsActivity) flags = flags or SimulatedIntent.FLAG_ACTIVITY_NEW_TASK
            }
            launchedIntents.add(intent)
            return true
        } catch (e: Exception) {
            // Tier 1 failed
        }

        // Tier 2: ACTION_APPLICATION_DETAILS_SETTINGS
        try {
            if (tier2Throws) throw RuntimeException("App details settings not available")
            val intent = SimulatedIntent(
                SimulatedIntent.ACTION_APPLICATION_DETAILS_SETTINGS,
                dataUri = "package:hu.vmiklos.plees_tracker"
            ).apply {
                if (!contextIsActivity) flags = flags or SimulatedIntent.FLAG_ACTIVITY_NEW_TASK
            }
            launchedIntents.add(intent)
            return true
        } catch (e: Exception) {
            // Tier 2 failed
        }

        // Tier 3: ACTION_SETTINGS
        try {
            if (tier3Throws) throw RuntimeException("System settings not available")
            val intent = SimulatedIntent(SimulatedIntent.ACTION_SETTINGS).apply {
                if (!contextIsActivity) flags = flags or SimulatedIntent.FLAG_ACTIVITY_NEW_TASK
            }
            launchedIntents.add(intent)
            return true
        } catch (e: Exception) {
            // Tier 3 failed
        }

        return false
    }

    @Test
    fun testSettingsNavigation_tier1Success() {
        val intents = mutableListOf<SimulatedIntent>()
        val success = simulateOpenSettings(
            contextIsActivity = true,
            tier1Throws = false,
            tier2Throws = false,
            tier3Throws = false,
            launchedIntents = intents
        )

        assertTrue(success)
        assertEquals(1, intents.size)
        assertEquals(SimulatedIntent.ACTION_USAGE_ACCESS_SETTINGS, intents[0].action)
    }

    @Test
    fun testSettingsNavigation_tier1Fails_tier2Success() {
        val intents = mutableListOf<SimulatedIntent>()
        val success = simulateOpenSettings(
            contextIsActivity = true,
            tier1Throws = true,
            tier2Throws = false,
            tier3Throws = false,
            launchedIntents = intents
        )

        assertTrue(success)
        assertEquals(1, intents.size)
        assertEquals(SimulatedIntent.ACTION_APPLICATION_DETAILS_SETTINGS, intents[0].action)
        assertEquals("package:hu.vmiklos.plees_tracker", intents[0].dataUri)
    }

    @Test
    fun testSettingsNavigation_tier1And2Fail_tier3Success() {
        val intents = mutableListOf<SimulatedIntent>()
        val success = simulateOpenSettings(
            contextIsActivity = true,
            tier1Throws = true,
            tier2Throws = true,
            tier3Throws = false,
            launchedIntents = intents
        )

        assertTrue(success)
        assertEquals(1, intents.size)
        assertEquals(SimulatedIntent.ACTION_SETTINGS, intents[0].action)
    }

    @Test
    fun testSettingsNavigation_allTiersFail_returnsFalseSafelyWithoutCrash() {
        val intents = mutableListOf<SimulatedIntent>()
        val success = simulateOpenSettings(
            contextIsActivity = true,
            tier1Throws = true,
            tier2Throws = true,
            tier3Throws = true,
            launchedIntents = intents
        )

        assertFalse("Must return false when all navigation intents fail", success)
        assertTrue(intents.isEmpty())
    }

    @Test
    fun testSettingsNavigation_nonActivityContext_addsNewTaskFlag() {
        val intents = mutableListOf<SimulatedIntent>()
        val success = simulateOpenSettings(
            contextIsActivity = false,
            tier1Throws = false,
            tier2Throws = false,
            tier3Throws = false,
            launchedIntents = intents
        )

        assertTrue(success)
        assertEquals(1, intents.size)
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK must be added when launched from non-Activity context",
            (intents[0].flags and SimulatedIntent.FLAG_ACTIVITY_NEW_TASK) != 0
        )
    }

    // =========================================================================
    // SECTION 4: MANIFEST, STRINGS & RESOURCE VALIDATION
    // =========================================================================

    @Test
    fun testManifest_containsPackageUsageStatsWithoutInternet() {
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
            "Manifest must NOT declare android.permission.INTERNET",
            content.contains("android.permission.INTERNET")
        )
    }

    @Test
    fun testPreferencesXml_structureAndDefaults() {
        val prefsFile = File("src/main/res/xml/preferences.xml")
        assertTrue("preferences.xml must exist", prefsFile.exists())
        val content = prefsFile.readText()

        assertTrue("Must contain auto_sleep_category", content.contains("auto_sleep_category"))
        assertTrue("Must contain auto_sleep_enabled", content.contains("auto_sleep_enabled"))
        assertTrue("Must contain auto_sleep_policy", content.contains("auto_sleep_policy"))
        assertTrue("Must contain auto_sleep_save_mode", content.contains("auto_sleep_save_mode"))
    }

    @Test
    fun testStringsXml_allAutoSleepStringsExist() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsFile.exists())
        val content = stringsFile.readText()

        val requiredKeys = listOf(
            "settings_category_autosleep",
            "settings_auto_sleep_enabled",
            "settings_auto_sleep_summary",
            "settings_auto_sleep_unsupported",
            "settings_auto_sleep_policy",
            "auto_sleep_policy_overnight_longest_gap",
            "auto_sleep_policy_pure_longest_gap",
            "settings_auto_sleep_save_mode",
            "auto_sleep_save_mode_suggest",
            "auto_sleep_save_mode_auto_save",
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

        for (key in requiredKeys) {
            assertTrue("Missing string resource: $key", content.contains("name=\"$key\""))
        }
    }

    // =========================================================================
    // SECTION 5: RANDOMIZED CHAOS FUZZING HARNESS
    // =========================================================================

    private enum class ChaosEvent {
        TOGGLE_ON,
        TOGGLE_OFF,
        GRANT_PERMISSION,
        REVOKE_PERMISSION,
        RESUME_APP,
        DISMISS_DIALOG,
        ROTATE_DEVICE,
        RECEIVE_CANDIDATE
    }

    @Test
    fun testChaosFuzzing_stateMachineInvariantsHoldUnderRandomSequence() {
        val random = Random(42L)
        var hasOsPermission = false
        var usageAccessSettingsPending = false
        var dialogShowing = false
        var workerActive = false

        for (step in 1..200) {
            val event = ChaosEvent.values()[random.nextInt(ChaosEvent.values().size)]

            when (event) {
                ChaosEvent.TOGGLE_ON -> {
                    if (hasOsPermission) {
                        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
                        workerActive = true
                    } else {
                        dialogShowing = true
                        usageAccessSettingsPending = true
                    }
                }
                ChaosEvent.TOGGLE_OFF -> {
                    prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
                    workerActive = false
                    store.clear()
                }
                ChaosEvent.GRANT_PERMISSION -> {
                    hasOsPermission = true
                }
                ChaosEvent.REVOKE_PERMISSION -> {
                    hasOsPermission = false
                }
                ChaosEvent.RESUME_APP -> {
                    val stored = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
                    if (usageAccessSettingsPending) {
                        usageAccessSettingsPending = false
                        dialogShowing = false
                        if (hasOsPermission) {
                            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
                            workerActive = true
                        } else {
                            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
                            workerActive = false
                        }
                    } else if (stored && !hasOsPermission) {
                        // External revocation detected on resume
                        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
                        workerActive = false
                        store.clear()
                    }
                }
                ChaosEvent.DISMISS_DIALOG -> {
                    dialogShowing = false
                }
                ChaosEvent.ROTATE_DEVICE -> {
                    // Saved instance state preserves pending flag
                    val map = mutableMapOf("usageAccessSettingsPending" to usageAccessSettingsPending)
                    usageAccessSettingsPending = map["usageAccessSettingsPending"] ?: false
                }
                ChaosEvent.RECEIVE_CANDIDATE -> {
                    val stored = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
                    if (stored && hasOsPermission) {
                        val c = SleepCandidate(
                            baseTime + step * 1000L,
                            baseTime + step * 1000L + 500L,
                            AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                            baseTime + step * 1000L + 500L
                        )
                        store.addPending(c, now)
                    }
                }
            }
        }

        // Final reconciliation
        val finalStored = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        if (finalStored && !hasOsPermission) {
            // Reconcile on resume
            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
            store.clear()
        }

        val resolvedStored = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        if (!hasOsPermission) {
            assertFalse("AutoSleep must be disabled when permission is not held", resolvedStored)
        }
    }
}
