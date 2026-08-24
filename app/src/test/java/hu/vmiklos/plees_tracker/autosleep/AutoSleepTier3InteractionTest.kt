/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 3: Cross-Feature Interactions E2E Tests for Plees AutoSleep.
 * Exercises pairwise feature interactions, mode switches, concurrency, multi-day lookback overlaps,
 * and permission revocation resilience.
 */
class AutoSleepTier3InteractionTest {

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

    @Test
    fun test_T3_Interaction_01_suggestToAutoSaveModeTransition() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val tStart = base + 23 * 3600 * 1000L
        val tStop = base + 31 * 3600 * 1000L // 8h
        val now = base + 36 * 3600 * 1000L

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(tStart, tStop)
        }

        // Phase 1: SUGGEST mode
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result1 = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result1.status)
        assertEquals(1, result1.queued)
        assertEquals(0, result1.autoSaved)
        assertEquals(1, store.getPendingCandidates().size)
        assertEquals(0, fakeDao.count())

        // Phase 2: Switch to AUTO_SAVE mode and clear pending (simulate user resolving or new scan)
        prefs.edit().putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE").apply()
        store.clear()

        val result2 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result2.status)
        assertEquals(0, result2.queued)
        assertEquals(1, result2.autoSaved)
        assertEquals(1, fakeDao.count())
    }

    @Test
    fun test_T3_Interaction_02_rejectedCandidateNeverAutoSavedInAutoSaveMode() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val tStart = base + 23 * 3600 * 1000L
        val tStop = base + 31 * 3600 * 1000L
        val now = base + 36 * 3600 * 1000L

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(tStart, tStop)
        }

        // Phase 1: Candidate is detected in SUGGEST mode, user rejects it
        val candidate = SleepCandidate(tStart, tStop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        store.markRejected(candidate, now)
        assertTrue(store.isRejected(candidate.fingerprint, now))

        // Phase 2: Mode changed to AUTO_SAVE
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.autoSaved)
        assertEquals(0, fakeDao.count())
    }

    @Test
    fun test_T3_Interaction_03_concurrentScansSerializedByMutex() = runBlocking {
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

        // Launch 5 concurrent scan tasks
        val deferreds = (1..5).map {
            async {
                backend.scan()
            }
        }
        val results = deferreds.awaitAll()

        // Exactly 1 candidate queued across all concurrent executions
        val totalQueued = results.sumOf { it.queued }
        assertEquals(1, totalQueued)
        assertEquals("Pending store must contain exactly 1 candidate", 1, store.getPendingCandidates().size)
    }

    @Test
    fun test_T3_Interaction_04_multiDayLookbackWithPartialRoomOverlaps() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 19, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val day1Base = cal.timeInMillis
        val day2Base = day1Base + 24 * 3600 * 1000L
        val day3Base = day2Base + 24 * 3600 * 1000L

        val night1Start = day1Base + 23 * 3600 * 1000L
        val night1Stop = day1Base + 31 * 3600 * 1000L // 8h

        val night2Start = day2Base + 23 * 3600 * 1000L
        val night2Stop = day2Base + 31 * 3600 * 1000L // 8h

        val night3Start = day3Base + 22 * 3600 * 1000L
        val night3Stop = day3Base + 30 * 3600 * 1000L // 8h

        val now = day3Base + 36 * 3600 * 1000L
        val unlocks = listOf(night1Start, night1Stop, night2Start, night2Stop, night3Start, night3Stop)

        // Setup Room Database:
        // Night 1: 100% covered by manual sleep
        fakeDao.insert(Sleep().apply {
            start = night1Start
            stop = night1Stop
        })
        // Night 2: 25% covered (2h out of 8h)
        fakeDao.insert(Sleep().apply {
            start = night2Start
            stop = night2Start + 2 * 3600 * 1000L
        })
        // Night 3: 0% covered

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = unlocks
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })

        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(3, result.discovered)
        // Night 1 suppressed (100%), Night 2 accepted (25% < 50%), Night 3 accepted (0%) -> 2 queued
        assertEquals(2, result.queued)
        assertEquals(2, store.getPendingCandidates().size)
    }

    @Test
    fun test_T3_Interaction_05_permissionRevocationAndRecoveryPreservesStore() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val tStart = base + 23 * 3600 * 1000L
        val tStop = base + 31 * 3600 * 1000L
        val now = base + 36 * 3600 * 1000L

        val hasPermission = AtomicBoolean(true)
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(tStart, tStop)
        }

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val backend = AutoSleepBackend(
            prefs, source, store, detector, fakeDao,
            hasPermissionProvider = { hasPermission.get() },
            nowProvider = { now }
        )

        // Step 1: Scan with permission -> 1 queued
        val res1 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, res1.status)
        assertEquals(1, store.getPendingCandidates().size)

        // Step 2: Permission revoked -> scan returns NO_PERMISSION, store intact
        hasPermission.set(false)
        val res2 = backend.scan()
        assertEquals(ScanStatus.NO_PERMISSION, res2.status)
        assertEquals(1, store.getPendingCandidates().size)

        // Step 3: Permission re-granted -> scan runs, deduplicates against pending, 0 new queued
        hasPermission.set(true)
        val res3 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, res3.status)
        assertEquals(0, res3.queued)
        assertEquals(1, store.getPendingCandidates().size)
    }

    @Test
    fun test_T3_Interaction_06_nocturnalWakeWithRoomOverlapInteraction() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis

        // Sleep sequence with midnight wake:
        // Gap 1: 23:30 to 03:00 (3.5h)
        // Gap 2: 03:00 to 03:05 (5m) -> rejected
        // Gap 3: 03:05 to 07:35 (4.5h) -> winning nocturnal segment
        val t2330 = base + (23 * 3600 + 30 * 60) * 1000L
        val t0300 = base + (27 * 3600) * 1000L
        val t0305 = base + (27 * 3600 + 5 * 60) * 1000L
        val t0735 = base + (31 * 3600 + 35 * 60) * 1000L
        val now = base + 40 * 3600 * 1000L

        // Room has manual sleep from 03:00 to 05:30 (covering 2h25m of the 4h30m gap = 53.7% > 50%)
        fakeDao.insert(Sleep().apply {
            start = t0300
            stop = base + (29 * 3600 + 30 * 60) * 1000L
        })

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t2330, t0300, t0305, t0735)
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })

        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered) // 03:05 to 07:35 selected
        assertEquals(0, result.queued)     // Suppressed due to >50% overlap with manual entry
        assertTrue(store.getPendingCandidates().isEmpty())
    }
}
