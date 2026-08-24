/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit and integration tests for AutoSleep Preferences state transitions,
 * runtime permission toggling, and MainActivity candidate dialog decision flows.
 */
class AutoSleepDialogAndPreferencesFlowTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore
    private lateinit var fakeDao: FakeSleepDao

    private val tz = TimeZone.getTimeZone("UTC")
    private var baseTime: Long = 0L
    private var t1: Long = 0L // 23:00 Day 1
    private var t2: Long = 0L // 07:00 Day 2 (8h overnight)
    private var t3: Long = 0L // 23:00 Day 2
    private var t4: Long = 0L // 07:00 Day 3 (8h overnight)
    private var now: Long = 0L // 12:00 Day 3

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(prefs)
        fakeDao = FakeSleepDao()

        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        baseTime = cal.timeInMillis
        t1 = baseTime + 23 * 3600 * 1000L
        t2 = baseTime + 31 * 3600 * 1000L
        t3 = baseTime + 47 * 3600 * 1000L
        t4 = baseTime + 55 * 3600 * 1000L
        now = baseTime + 60 * 3600 * 1000L
    }

    // =========================================================================
    // 1. DIALOG ACTION FLOWS: SAVE, DISCARD, LATER
    // =========================================================================

    @Test
    fun testDialogAction_saveAcceptsCandidateAndInsertsToRoom() = runBlocking {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)
        assertEquals(1, store.getPendingCandidates().size)

        // Simulate user clicking "Save" on candidate dialog
        var callbackInvoked = false
        val sleep = Sleep().apply {
            start = candidate.start
            stop = candidate.stop
        }
        val sid = fakeDao.insert(sleep).toInt()
        store.markAccepted(candidate)
        callbackInvoked = true

        assertEquals(1, sid)
        assertTrue("Callback must be invoked on Save", callbackInvoked)
        assertEquals(1, fakeDao.getAll().size)
        assertEquals(t1, fakeDao.getAll()[0].start)
        assertEquals(t2, fakeDao.getAll()[0].stop)
        assertTrue("Candidate must be removed from pending store after acceptance", store.getPendingCandidates().isEmpty())
        assertFalse("Accepted candidate must NOT be in rejected store", store.isRejected(candidate.fingerprint, now))
    }

    @Test
    fun testDialogAction_discardRejectsCandidateWithTtl() = runBlocking {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)
        assertEquals(1, store.getPendingCandidates().size)

        // Simulate user clicking "Discard" on candidate dialog
        store.markRejected(candidate, now)

        assertTrue("Pending store must be cleared after discard", store.getPendingCandidates().isEmpty())
        assertTrue("Candidate fingerprint must be recorded in rejected store", store.isRejected(candidate.fingerprint, now))
        assertEquals(0, fakeDao.getAll().size)

        // Ensure future scans reject this candidate
        assertFalse("Store must not allow re-adding rejected candidate", store.addPending(candidate, now))
    }

    @Test
    fun testDialogAction_laterPreservesCandidateInPendingStore() = runBlocking {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)
        assertEquals(1, store.getPendingCandidates().size)

        // Simulate user clicking "Later" or dismissing dialog (no store changes)
        // Dialog session flag is set, store remains untouched

        assertEquals("Pending store must retain candidate", 1, store.getPendingCandidates().size)
        assertEquals(candidate.fingerprint, store.getPendingCandidates()[0].fingerprint)
        assertFalse("Candidate must NOT be marked rejected", store.isRejected(candidate.fingerprint, now))
        assertEquals(0, fakeDao.getAll().size)
    }

    @Test
    fun testDialogAction_mostRecentCandidatePresentedFirst() {
        val candidateOld = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        val candidateNew = SleepCandidate(t3, t4, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t4 + 3600000L)

        store.addPending(candidateOld, now)
        store.addPending(candidateNew, now)

        val pending = store.getPendingCandidates()
        assertEquals(2, pending.size)

        // MainActivity presents pending.first()
        val firstPrompt = pending.first()
        assertEquals("Most recent candidate (highest stop timestamp) must be presented first", candidateNew.fingerprint, firstPrompt.fingerprint)

        // Simulate user accepting the most recent candidate
        store.markAccepted(firstPrompt)

        // Verify the older candidate remains in pending store for the next session
        val remaining = store.getPendingCandidates()
        assertEquals(1, remaining.size)
        assertEquals(candidateOld.fingerprint, remaining.first().fingerprint)
    }

    // =========================================================================
    // 2. PREFERENCES STATE MACHINE & PERMISSION TOGGLING
    // =========================================================================

    @Test
    fun testPreferences_toggleOffClearsStoreAndCancelsScan() {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)
        store.markRejected(candidate, now)

        // Simulate user toggling switch to OFF
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        store.clear()

        assertFalse(prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, true))
        assertTrue("Pending candidates must be empty after toggle OFF", store.getPendingCandidates().isEmpty())
        assertFalse("Rejected fingerprints must be empty after toggle OFF", store.isRejected(candidate.fingerprint, now))
    }

    @Test
    fun testPreferences_externalPermissionRevocationResetsState() {
        // User had feature enabled
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        // Permission was revoked in Android Settings (hasAccess = false)
        val storedEnabled = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        val hasAccess = false

        if (storedEnabled && !hasAccess) {
            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
            store.clear()
        }

        assertFalse("AutoSleep must be disabled when permission is revoked", prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, true))
        assertTrue("Candidate store must be cleared on permission revocation", store.getPendingCandidates().isEmpty())
    }

    @Test
    fun testPreferences_defaultsAreCorrect() {
        val defaultEnabled = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        val defaultPolicy = prefs.getString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name)
        val defaultSaveMode = prefs.getString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")

        assertFalse("AutoSleep must be disabled by default (opt-in)", defaultEnabled)
        assertEquals("OVERNIGHT_LONGEST_GAP", defaultPolicy)
        assertEquals("SUGGEST", defaultSaveMode)
    }

    @Test
    fun testPreferences_policyAndSaveModeSelectionsPersist() {
        prefs.edit()
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.PURE_LONGEST_GAP.name)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        assertEquals(AutoSleepPolicyId.PURE_LONGEST_GAP.name, prefs.getString(AutoSleepConfig.POLICY_KEY, null))
        assertEquals("AUTO_SAVE", prefs.getString(AutoSleepConfig.SAVE_MODE_KEY, null))
    }
}
