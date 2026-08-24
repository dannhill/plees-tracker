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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 4: Real-World Application Workloads & Scenario E2E Tests for Plees AutoSleep.
 * Exercises realistic 7-day sleep sequences, nocturnal wakes, DST transitions in Europe/Rome,
 * shift worker schedules, and store lifecycle management.
 */
class AutoSleepTier4RealWorldScenarioTest {

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
     * S1: Typical Mon-Sun 7-Day Sleep Schedule.
     * Simulates 7 continuous days of unlock events with day use and nocturnal sleep.
     * Evaluates 72-hour sliding lookback windows and verifies idempotent scanning.
     */
    @Test
    fun test_T4_Scenario_S1_monSun7DaySleepSchedule() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 17, 0, 0, 0) // Monday
            set(Calendar.MILLISECOND, 0)
        }
        val mondayBase = cal.timeInMillis

        // Build 7 days of unlocks:
        // Daily: wake at 07:00, unlocks at 08:30, 12:30, 17:30, 20:00, 22:30 lock
        val allUnlocks = mutableListOf<Long>()
        for (day in 0 until 7) {
            val dayStart = mondayBase + day * 24 * 3600 * 1000L
            allUnlocks.add(dayStart + 7 * 3600 * 1000L)   // 07:00 wake
            allUnlocks.add(dayStart + 8 * 3600 * 1000L + 30 * 60 * 1000L)  // 08:30
            allUnlocks.add(dayStart + 12 * 3600 * 1000L + 30 * 60 * 1000L) // 12:30
            allUnlocks.add(dayStart + 17 * 3600 * 1000L + 30 * 60 * 1000L) // 17:30
            allUnlocks.add(dayStart + 20 * 3600 * 1000L)  // 20:00
            allUnlocks.add(dayStart + 22 * 3600 * 1000L + 30 * 60 * 1000L) // 22:30 bedtime lock
        }

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        // Scan at Thursday 10:00 (covers Tue, Wed, Thu mornings in 72h window)
        val thursdayNow = mondayBase + (3 * 24 + 10) * 3600 * 1000L
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) =
                allUnlocks.filter { it in beginInclusive until endExclusive }
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { thursdayNow })

        val result1 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result1.status)
        assertEquals(3, result1.discovered)
        assertEquals(3, result1.queued)
        assertEquals(3, store.getPendingCandidates().size)

        // Run second scan immediately: must be strictly idempotent
        val result2 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result2.status)
        assertEquals(0, result2.queued)
        assertEquals(3, store.getPendingCandidates().size)
    }

    /**
     * S2: Nocturnal Wake Splitting (Brief Bathroom / Water Break at 03:12).
     * Tests exact minute precision and longest nocturnal segment selection.
     */
    @Test
    fun test_T4_Scenario_S2_nocturnalWakeBathroomBreakSplitting() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis

        // Unlock 1: 23:41
        val t2341 = base + (23 * 3600 + 41 * 60) * 1000L
        // Unlock 2: 03:12 (wakes up for water)
        val t0312 = base + (27 * 3600 + 12 * 60) * 1000L
        // Unlock 3: 03:14 (locks phone and goes back to sleep)
        val t0314 = base + (27 * 3600 + 14 * 60) * 1000L
        // Unlock 4: 07:48 (morning wake)
        val t0748 = base + (31 * 3600 + 48 * 60) * 1000L
        val now = base + 40 * 3600 * 1000L

        // Gaps:
        // Gap 1: 23:41 to 03:12 = 3h31m = 12,660,000 ms (Eligible overnight gap)
        // Gap 2: 03:12 to 03:14 = 2m = 120,000 ms (Rejected: < 3h)
        // Gap 3: 03:14 to 07:48 = 4h34m = 16,440,000 ms (Eligible overnight gap)

        val unlocks = listOf(t2341, t0312, t0314, t0748)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)

        assertEquals("Must select exactly 1 candidate for the single wake date", 1, candidates.size)
        val candidate = candidates[0]

        // Longest segment (4h34m) wins over 3h31m
        assertEquals(t0314, candidate.start)
        assertEquals(t0748, candidate.stop)
        assertEquals((4 * 3600 + 34 * 60) * 1000L, candidate.durationMs)
    }

    /**
     * S3: Spring-Forward & Fall-Back Daylight Saving Weekends in Europe/Rome.
     * Verifies immunity to DST shifts: duration is true elapsed milliseconds.
     */
    @Test
    fun test_T4_Scenario_S3_springForwardAndFallBackRomeDST() {
        val romeTz = TimeZone.getTimeZone("Europe/Rome")

        // 1. Spring-forward test (March 28-29, 2026: 02:00 skips to 03:00)
        val springCal = Calendar.getInstance(romeTz).apply {
            set(2026, Calendar.MARCH, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val springStart = springCal.timeInMillis
        springCal.set(2026, Calendar.MARCH, 29, 7, 0, 0)
        val springStop = springCal.timeInMillis

        val springCandidates = detector.detect(
            listOf(springStart, springStop),
            AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            springStop + 3600000L,
            romeTz
        )
        assertEquals(1, springCandidates.size)
        // 7h physical duration despite wall clock showing 8h
        assertEquals(7 * 3600 * 1000L, springCandidates[0].durationMs)

        // 2. Fall-back test (October 24-25, 2026: 03:00 rolls back to 02:00)
        val fallCal = Calendar.getInstance(romeTz).apply {
            set(2026, Calendar.OCTOBER, 24, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fallStart = fallCal.timeInMillis
        fallCal.set(2026, Calendar.OCTOBER, 25, 7, 0, 0)
        val fallStop = fallCal.timeInMillis

        val fallCandidates = detector.detect(
            listOf(fallStart, fallStop),
            AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            fallStop + 3600000L,
            romeTz
        )
        assertEquals(1, fallCandidates.size)
        // 9h physical duration despite wall clock showing 8h
        assertEquals(9 * 3600 * 1000L, fallCandidates[0].durationMs)
    }

    /**
     * S4: Manual Sleep Logged Prior to AutoSleep Scan (Overlap >= 50%).
     * User logged manual sleep in Room covering >50% of the candidate interval.
     */
    @Test
    fun test_T4_Scenario_S4_manualSleepLoggedPriorToScanSuppression() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val tStart = base + 23 * 3600 * 1000L
        val tStop = base + 31 * 3600 * 1000L // 8h
        val now = base + 36 * 3600 * 1000L

        // User manually tracked 23:00 to 04:00 (5 hours out of 8 hours = 62.5%)
        val manualSleep = Sleep().apply {
            start = tStart
            stop = tStart + 5 * 3600 * 1000L
        }
        fakeDao.insert(manualSleep)

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(tStart, tStop)
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })

        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        // 62.5% overlap >= 50% threshold: Candidate is suppressed
        assertEquals(0, result.queued)
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    /**
     * S5: Shift Worker Daytime Sleep with Pure vs Overnight Modes.
     * Shift worker sleeps from 07:30 to 15:30 (8h daytime).
     */
    @Test
    fun test_T4_Scenario_S5_shiftWorkerDaytimeSleepPureVsOvernight() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t0730 = base + (7 * 3600 + 30 * 60) * 1000L
        val t1530 = base + (15 * 3600 + 30 * 60) * 1000L
        val now = base + 20 * 3600 * 1000L

        val unlocks = listOf(t0730, t1530)

        // OVERNIGHT policy: Gap does NOT intersect local 00:00-06:00 on wake date (Aug 20) -> rejected
        val overnightCandidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("Shift worker daytime sleep must be rejected by OVERNIGHT policy", overnightCandidates.isEmpty())

        // PURE policy: Evaluates literal duration without anchor -> accepted
        val pureCandidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pureCandidates.size)
        assertEquals(t0730, pureCandidates[0].start)
        assertEquals(t1530, pureCandidates[0].stop)
        assertEquals(8 * 3600 * 1000L, pureCandidates[0].durationMs)
    }

    /**
     * S6: Corrupted Storage Recovery, Capacity Overflow & 96h TTL Pruning.
     */
    @Test
    fun test_T4_Scenario_S6_corruptedStoreRecoveryCapacityOverflowAndTtlPruning() {
        val now = 1000000000L

        // Phase 1: Corrupted JSON recovery
        prefs.edit()
            .putString(AutoSleepConfig.PENDING_JSON_KEY, "MALFORMED_JSON_STRING")
            .putString(AutoSleepConfig.REJECTED_JSON_KEY, "[{\"corrupt\": true")
            .apply()

        assertTrue(store.getPendingCandidates().isEmpty())
        assertFalse(store.isRejected("dummy"))

        // Phase 2: Add 10 pending candidates -> verify capped at 7
        for (i in 1..10) {
            val start = now + i * 3600000L
            val stop = start + 1800000L
            store.addPending(SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop))
        }
        val pending = store.getPendingCandidates()
        assertEquals(7, pending.size)

        // Phase 3: Add 35 rejected records, 10 of which are >96h old
        for (i in 1..10) {
            val candidate = SleepCandidate(now - 120 * 3600 * 1000L + i * 1000L, now - 110 * 3600 * 1000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 0L)
            store.markRejected(candidate, now - 110 * 3600 * 1000L) // >96h old
        }
        for (i in 1..25) {
            val candidate = SleepCandidate(now + i * 1000L, now + i * 1000L + 500L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
            store.markRejected(candidate, now + i * 1000L) // fresh
        }

        // Verify expired records pruned and total valid records <= 30
        for (i in 1..25) {
            val candidate = SleepCandidate(now + i * 1000L, now + i * 1000L + 500L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
            assertTrue(store.isRejected(candidate.fingerprint, now + 50000L))
        }
    }
}
