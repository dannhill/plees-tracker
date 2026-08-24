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
 * Master End-to-End Test Suite for Plees AutoSleep.
 * Validates complete end-to-end user journeys from raw unlock event ingestion,
 * through algorithmic detection, candidate filtering, Room overlap suppression,
 * and state persistence in both SUGGEST and AUTO_SAVE modes.
 */
class AutoSleepE2ETest {

    private lateinit var detector: AutoSleepDetector
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore
    private lateinit var fakeDao: FakeSleepDao

    @Before
    fun setUp() {
        detector = AutoSleepDetector()
        prefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(prefs)
        fakeDao = FakeSleepDao()
    }

    /**
     * E2E Journey 1: SUGGEST Mode Full Lifecycle.
     * 1. User enables AutoSleep with default SUGGEST mode.
     * 2. Overnight unlocks occur (bedtime 23:15, morning wake 07:30).
     * 3. Background scan runs -> candidate is detected and stored in pending store.
     * 4. MainActivity opens -> pending candidate is retrieved for dialog confirmation.
     * 5. User clicks "Save" -> candidate is saved to Room, removed from pending.
     * 6. Subsequent scan runs -> 100% overlap with saved record suppresses duplicate candidate.
     */
    @Test
    fun test_E2E_Journey_01_suggestModeFullLifecycle() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis

        val bedtimeLock = base + (23 * 3600 + 15 * 60) * 1000L
        val morningWake = base + (31 * 3600 + 30 * 60) * 1000L // 07:30 next day
        val now = base + 36 * 3600 * 1000L

        // Step 1: User enables AutoSleep (SUGGEST mode)
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(bedtimeLock, morningWake)
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })

        // Step 2: Background scan executes
        val scanResult = backend.scan()
        assertEquals(ScanStatus.SUCCESS, scanResult.status)
        assertEquals(1, scanResult.discovered)
        assertEquals(1, scanResult.queued)
        assertEquals(0, scanResult.autoSaved)

        // Step 3: Verify candidate in pending store
        val pendingList = store.getPendingCandidates()
        assertEquals(1, pendingList.size)
        val candidate = pendingList[0]
        assertEquals(bedtimeLock, candidate.start)
        assertEquals(morningWake, candidate.stop)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, candidate.policy)

        // Step 4: User accepts candidate via UI (Save action)
        val sleepRecord = Sleep().apply {
            start = candidate.start
            stop = candidate.stop
        }
        fakeDao.insert(sleepRecord)
        store.markAccepted(candidate)

        // Step 5: Verify Room contains the sleep record and pending store is cleared
        assertEquals(1, fakeDao.count())
        val savedSleep = fakeDao.getAll()[0]
        assertEquals(bedtimeLock, savedSleep.start)
        assertEquals(morningWake, savedSleep.stop)
        assertTrue(store.getPendingCandidates().isEmpty())

        // Step 6: Subsequent scan runs -> candidate suppressed by Room overlap
        val followUpScan = backend.scan()
        assertEquals(ScanStatus.SUCCESS, followUpScan.status)
        assertEquals(1, followUpScan.discovered)
        assertEquals(0, followUpScan.queued)
        assertEquals(0, followUpScan.autoSaved)
    }

    /**
     * E2E Journey 2: AUTO_SAVE Mode Full Lifecycle.
     * 1. User sets save mode to AUTO_SAVE.
     * 2. Periodic background worker triggers scan.
     * 3. Detected sleep is automatically inserted into Room and marked accepted.
     * 4. Room DAO contains the newly inserted record with correct timestamps.
     * 5. Repeated scans do not create duplicate Room records.
     */
    @Test
    fun test_E2E_Journey_02_autoSaveModeFullLifecycle() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis

        val tStart = base + (22 * 3600 + 45 * 60) * 1000L
        val tStop = base + (31 * 3600) * 1000L // 07:00 next day (8h15m)
        val now = base + 36 * 3600 * 1000L

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(tStart, tStop)
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })

        // Execute scan
        val result1 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result1.status)
        assertEquals(1, result1.discovered)
        assertEquals(0, result1.queued)
        assertEquals(1, result1.autoSaved)

        assertEquals(1, fakeDao.count())
        val saved = fakeDao.getAll()[0]
        assertEquals(tStart, saved.start)
        assertEquals(tStop, saved.stop)

        // Second scan must be idempotent
        val result2 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result2.status)
        assertEquals(0, result2.autoSaved)
        assertEquals(1, fakeDao.count())
    }

    /**
     * E2E Journey 3: User Rejection & Rejection TTL Memory.
     * 1. Candidate detected in SUGGEST mode.
     * 2. User taps "Discard" -> marked rejected.
     * 3. Candidate removed from pending; fingerprint saved in rejected list.
     * 4. Subsequent scans within 96h ignore this candidate.
     */
    @Test
    fun test_E2E_Journey_03_rejectionMemoryLifecycle() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val tStart = base + 23 * 3600 * 1000L
        val tStop = base + 31 * 3600 * 1000L
        val now = base + 36 * 3600 * 1000L

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(tStart, tStop)
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })

        // Initial scan queues candidate
        backend.scan()
        assertEquals(1, store.getPendingCandidates().size)
        val candidate = store.getPendingCandidates()[0]

        // User discards candidate
        store.markRejected(candidate, now)
        assertTrue(store.getPendingCandidates().isEmpty())
        assertTrue(store.isRejected(candidate.fingerprint, now))

        // Next scan ignores rejected candidate
        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.queued)
        assertEquals(0, result.autoSaved)
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    /**
     * E2E Journey 4: Pure Longest Gap vs Overnight Longest Gap Policy Switching.
     */
    @Test
    fun test_E2E_Journey_04_policySwitchingBehavior() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis

        // Day gap: 11:00 to 20:00 (9.0h)
        // Night gap: 23:30 to 07:00 (7.5h)
        val t1100 = base + 11 * 3600 * 1000L
        val t2000 = base + 20 * 3600 * 1000L
        val t2330 = base + (23 * 3600 + 30 * 60) * 1000L
        val t0700 = base + (31 * 3600) * 1000L
        val now = base + 40 * 3600 * 1000L

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1100, t2000, t2330, t0700)
        }

        // Test 1: Under OVERNIGHT policy -> selects 7.5h night gap
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        var backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        backend.scan()

        var pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        assertEquals(t2330, pending[0].start)
        assertEquals(t0700, pending[0].stop)

        // Clear store and switch to PURE policy -> selects 9.0h day gap
        store.clear()
        prefs.edit()
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.PURE_LONGEST_GAP.name)
            .apply()

        backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        backend.scan()

        pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        assertEquals(t1100, pending[0].start)
        assertEquals(t2000, pending[0].stop)
    }
}
