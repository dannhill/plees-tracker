/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for AutoSleepDetector covering UT-DET-001 through UT-DET-017.
 * Pure Kotlin unit tests running under standard JUnit 4.
 */
class AutoSleepDetectorUnitTest {

    private lateinit var detector: AutoSleepDetector

    @Before
    fun setUp() {
        detector = AutoSleepDetector()
    }

    /**
     * UT-DET-001: Empty unlocks list
     */
    @Test
    fun testUT_DET_001_emptyUnlocksList() {
        val now = 1000000000L
        val tz = TimeZone.getTimeZone("UTC")
        val unlocks = emptyList<Long>()

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue(gaps.isEmpty())

        val pureCandidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertTrue(pureCandidates.isEmpty())

        val overnightCandidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue(overnightCandidates.isEmpty())
    }

    /**
     * UT-DET-002: Single unlock event
     */
    @Test
    fun testUT_DET_002_singleUnlockEvent() {
        val now = 1000000000L
        val tz = TimeZone.getTimeZone("UTC")
        val unlocks = listOf(1000000000L)

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue(gaps.isEmpty())

        val pureCandidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertTrue(pureCandidates.isEmpty())

        val overnightCandidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue(overnightCandidates.isEmpty())
    }

    /**
     * UT-DET-003: Two unlock events (8h overnight gap)
     */
    @Test
    fun testUT_DET_003_twoUnlockEvents() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tStart = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 7, 0, 0)
        val tStop = cal.timeInMillis
        val now = tStop + 3600000L

        val unlocks = listOf(tStart, tStop)

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertEquals(1, gaps.size)
        assertEquals(tStart, gaps[0].start)
        assertEquals(tStop, gaps[0].stop)
        assertEquals(28800000L, gaps[0].durationMs)

        val pureCandidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pureCandidates.size)
        assertEquals(tStart, pureCandidates[0].start)
        assertEquals(tStop, pureCandidates[0].stop)
        assertEquals(AutoSleepPolicyId.PURE_LONGEST_GAP, pureCandidates[0].policy)

        val overnightCandidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, overnightCandidates.size)
        assertEquals(tStart, overnightCandidates[0].start)
        assertEquals(tStop, overnightCandidates[0].stop)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, overnightCandidates[0].policy)
    }

    /**
     * UT-DET-004: Unsorted unlock events
     */
    @Test
    fun testUT_DET_004_unsortedUnlockEvents() {
        val tz = TimeZone.getTimeZone("UTC")
        val t1 = 1000000000L
        val t2 = t1 + 3600000L
        val t3 = t1 + 28800000L
        val now = t3 + 3600000L

        val unsortedUnlocks = listOf(t3, t1, t2)

        val gaps = detector.normalizeUnlocks(unsortedUnlocks, now)
        assertEquals(2, gaps.size)
        assertEquals(t1, gaps[0].start)
        assertEquals(t2, gaps[0].stop)
        assertEquals(t2, gaps[1].start)
        assertEquals(t3, gaps[1].stop)

        val pureCandidates = detector.detect(unsortedUnlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pureCandidates.size)
        assertEquals(t2, pureCandidates[0].start)
        assertEquals(t3, pureCandidates[0].stop)
    }

    /**
     * UT-DET-005: Duplicate timestamps
     */
    @Test
    fun testUT_DET_005_duplicateTimestamps() {
        val tz = TimeZone.getTimeZone("UTC")
        val t1 = 1000000000L
        val t2 = t1 + 28800000L
        val now = t2 + 3600000L

        val duplicateUnlocks = listOf(t1, t1, t2, t2, t2)

        val gaps = detector.normalizeUnlocks(duplicateUnlocks, now)
        assertEquals(1, gaps.size)
        assertEquals(t1, gaps[0].start)
        assertEquals(t2, gaps[0].stop)
        assertEquals(28800000L, gaps[0].durationMs)

        val pureCandidates = detector.detect(duplicateUnlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pureCandidates.size)
        assertEquals(t1, pureCandidates[0].start)
        assertEquals(t2, pureCandidates[0].stop)
    }

    /**
     * UT-DET-006: PURE_LONGEST_GAP normal nocturnal sequence
     */
    @Test
    fun testUT_DET_006_pureLongestGapNormal() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t2134 = base + (21 * 3600 + 34 * 60) * 1000L
        val t2217 = base + (22 * 3600 + 17 * 60) * 1000L
        val t2303 = base + (23 * 3600 + 3 * 60) * 1000L
        val t2348 = base + (23 * 3600 + 48 * 60) * 1000L
        val t0736 = base + (31 * 3600 + 36 * 60) * 1000L // Aug 21 07:36
        val t0751 = base + (31 * 3600 + 51 * 60) * 1000L
        val now = base + 36 * 3600 * 1000L

        val unlocks = listOf(t2134, t2217, t2303, t2348, t0736, t0751)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)

        assertEquals(1, candidates.size)
        assertEquals(t2348, candidates[0].start)
        assertEquals(t0736, candidates[0].stop)
        assertEquals((7 * 3600 + 48 * 60) * 1000L, candidates[0].durationMs)
        assertEquals(AutoSleepPolicyId.PURE_LONGEST_GAP, candidates[0].policy)
    }

    /**
     * UT-DET-007: PURE_LONGEST_GAP daytime gap longer than night
     */
    @Test
    fun testUT_DET_007_pureLongestGapDayGreaterThanNight() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t0810 = base + (8 * 3600 + 10 * 60) * 1000L
        val t1200 = base + 12 * 3600 * 1000L
        val t2030 = base + (20 * 3600 + 30 * 60) * 1000L // 8.5h day gap
        val t2350 = base + (23 * 3600 + 50 * 60) * 1000L
        val t0720 = base + (31 * 3600 + 20 * 60) * 1000L // 7.5h night gap
        val now = base + 36 * 3600 * 1000L

        val unlocks = listOf(t0810, t1200, t2030, t2350, t0720)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)

        assertEquals(1, candidates.size)
        assertEquals(t1200, candidates[0].start)
        assertEquals(t2030, candidates[0].stop)
        assertEquals((8 * 3600 + 30 * 60) * 1000L, candidates[0].durationMs)
    }

    /**
     * UT-DET-008: OVERNIGHT_LONGEST_GAP rejects daytime long gap
     */
    @Test
    fun testUT_DET_008_overnightRejectsDaytimeLongGap() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t0810 = base + (8 * 3600 + 10 * 60) * 1000L
        val t1200 = base + 12 * 3600 * 1000L
        val t2030 = base + (20 * 3600 + 30 * 60) * 1000L // 8.5h day gap
        val t2350 = base + (23 * 3600 + 50 * 60) * 1000L
        val t0720 = base + (31 * 3600 + 20 * 60) * 1000L // 7.5h night gap
        val now = base + 36 * 3600 * 1000L

        val unlocks = listOf(t0810, t1200, t2030, t2350, t0720)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)

        assertEquals(1, candidates.size)
        assertEquals(t2350, candidates[0].start)
        assertEquals(t0720, candidates[0].stop)
        assertEquals((7 * 3600 + 30 * 60) * 1000L, candidates[0].durationMs)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, candidates[0].policy)
    }

    /**
     * UT-DET-009: OVERNIGHT_LONGEST_GAP rejects short gap (< 3h)
     */
    @Test
    fun testUT_DET_009_overnightRejectsShortGapLessThan3h() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t0030 = base + 30 * 60 * 1000L
        val t0245 = base + (2 * 3600 + 45 * 60) * 1000L // 2h15m = 8,100,000 ms (< 10,800,000 ms)
        val now = base + 10 * 3600 * 1000L

        val unlocks = listOf(t0030, t0245)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)

        assertTrue("Gap < 3 hours must be rejected by overnight policy", candidates.isEmpty())
    }

    /**
     * UT-DET-010: OVERNIGHT_LONGEST_GAP rejects long gap (> 16h)
     */
    @Test
    fun testUT_DET_010_overnightRejectsLongGapGreaterThan16h() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t2000 = base + 20 * 3600 * 1000L
        val t1330 = base + (37 * 3600 + 30 * 60) * 1000L // 17h30m = 63,000,000 ms (> 57,600,000 ms)
        val now = base + 40 * 3600 * 1000L

        val unlocks = listOf(t2000, t1330)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)

        assertTrue("Gap > 16 hours must be rejected by overnight policy", candidates.isEmpty())
    }

    /**
     * UT-DET-011: OVERNIGHT_LONGEST_GAP gap crosses midnight
     */
    @Test
    fun testUT_DET_011_overnightGapCrossesMidnight() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t2230 = base + (22 * 3600 + 30 * 60) * 1000L
        val t0715 = base + (31 * 3600 + 15 * 60) * 1000L // Aug 21 07:15
        val now = base + 36 * 3600 * 1000L

        val unlocks = listOf(t2230, t0715)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)

        assertEquals(1, candidates.size)
        assertEquals(t2230, candidates[0].start)
        assertEquals(t0715, candidates[0].stop)
        assertEquals((8 * 3600 + 45 * 60) * 1000L, candidates[0].durationMs)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, candidates[0].policy)
    }

    /**
     * UT-DET-012: Tie-breaker selects most recent stop
     */
    @Test
    fun testUT_DET_012_tieBreakerSelectsMostRecent() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val tA_start = base + 23 * 3600 * 1000L
        val tA_stop = base + 31 * 3600 * 1000L // 8h gap (stop = Aug 21 07:00)

        // Daytime unlocks to prevent an accidental 16h day gap between tA_stop and tB_start
        val tDay1 = base + 35 * 3600 * 1000L // 11:00 (4h gap)
        val tDay2 = base + 39 * 3600 * 1000L // 15:00 (4h gap)
        val tDay3 = base + 43 * 3600 * 1000L // 19:00 (4h gap)

        val tB_start = base + 47 * 3600 * 1000L // Aug 21 23:00 (4h gap from 19:00)
        val tB_stop = base + 55 * 3600 * 1000L  // Aug 22 07:00 (8h gap)

        val now = base + 60 * 3600 * 1000L
        val unlocks = listOf(tA_start, tA_stop, tDay1, tDay2, tDay3, tB_start, tB_stop)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)

        assertEquals(1, candidates.size)
        assertEquals("Tie-breaker on equal duration must select the most recent stop timestamp", tB_start, candidates[0].start)
        assertEquals(tB_stop, candidates[0].stop)
    }

    /**
     * UT-DET-013: Future timestamp rejected
     */
    @Test
    fun testUT_DET_013_futureTimestampRejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t2200 = base + 22 * 3600 * 1000L
        val t0700 = base + 31 * 3600 * 1000L // Aug 21 07:00
        val t1000 = base + 34 * 3600 * 1000L // Aug 21 10:00 (future relative to now)
        val now = base + 32 * 3600 * 1000L   // Aug 21 08:00

        val unlocks = listOf(t2200, t0700, t1000)

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertEquals(1, gaps.size)
        assertEquals(t2200, gaps[0].start)
        assertEquals(t0700, gaps[0].stop)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(t2200, candidates[0].start)
        assertEquals(t0700, candidates[0].stop)
    }

    /**
     * UT-DET-014: Multiple wake dates in 72h lookback window
     */
    @Test
    fun testUT_DET_014_multipleWakeDatesIn72hLookback() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 19, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val day1Base = cal.timeInMillis
        val day2Base = day1Base + 24 * 3600 * 1000L
        val day3Base = day2Base + 24 * 3600 * 1000L

        val n1Start = day1Base + 23 * 3600 * 1000L
        val n1Stop = day1Base + 31 * 3600 * 1000L // Aug 20 07:00 (8h)

        val n2Start = day2Base + (23 * 3600 + 30 * 60) * 1000L
        val n2Stop = day2Base + (31 * 3600 + 30 * 60) * 1000L // Aug 21 07:30 (8h)

        val n3Start = day3Base + 22 * 3600 * 1000L
        val n3Stop = day3Base + (30 * 3600 + 30 * 60) * 1000L // Aug 22 06:30 (8.5h)

        val now = day3Base + 36 * 3600 * 1000L
        val unlocks = listOf(n1Start, n1Stop, n2Start, n2Stop, n3Start, n3Stop)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)

        assertEquals("Must return exactly 1 candidate per wake date", 3, candidates.size)
        // Must be sorted by stop descending
        assertEquals(n3Stop, candidates[0].stop)
        assertEquals(n3Start, candidates[0].start)
        assertEquals(n2Stop, candidates[1].stop)
        assertEquals(n2Start, candidates[1].start)
        assertEquals(n1Stop, candidates[2].stop)
        assertEquals(n1Start, candidates[2].start)
        assertTrue(candidates[0].stop > candidates[1].stop)
        assertTrue(candidates[1].stop > candidates[2].stop)
    }

    /**
     * UT-DET-015: DST Spring-Forward in Europe/Rome
     */
    @Test
    fun testUT_DET_015_dstSpringForwardEuropeRome() {
        val romeTz = TimeZone.getTimeZone("Europe/Rome")
        // March 29, 2026: Europe/Rome spring-forward at 02:00 -> 03:00 (+1h)
        val cal = Calendar.getInstance(romeTz).apply {
            set(2026, Calendar.MARCH, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis

        cal.set(2026, Calendar.MARCH, 29, 7, 0, 0)
        val stopMs = cal.timeInMillis

        // True physical duration is 7 hours (25,200,000 ms), though wall clock shows 8h difference
        val expectedDurationMs = 7 * 3600 * 1000L
        assertEquals(expectedDurationMs, stopMs - startMs)

        val now = stopMs + 3600000L
        val unlocks = listOf(startMs, stopMs)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, romeTz)

        assertEquals(1, candidates.size)
        assertEquals(startMs, candidates[0].start)
        assertEquals(stopMs, candidates[0].stop)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, candidates[0].policy)
    }

    /**
     * UT-DET-016: DST Fall-Back in Europe/Rome
     */
    @Test
    fun testUT_DET_016_dstFallBackEuropeRome() {
        val romeTz = TimeZone.getTimeZone("Europe/Rome")
        // October 25, 2026: Europe/Rome fall-back at 03:00 -> 02:00 (-1h)
        val cal = Calendar.getInstance(romeTz).apply {
            set(2026, Calendar.OCTOBER, 24, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis

        cal.set(2026, Calendar.OCTOBER, 25, 7, 0, 0)
        val stopMs = cal.timeInMillis

        // True physical duration is 9 hours (32,400,000 ms), though wall clock shows 8h difference
        val expectedDurationMs = 9 * 3600 * 1000L
        assertEquals(expectedDurationMs, stopMs - startMs)

        val now = stopMs + 3600000L
        val unlocks = listOf(startMs, stopMs)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, romeTz)

        assertEquals(1, candidates.size)
        assertEquals(startMs, candidates[0].start)
        assertEquals(stopMs, candidates[0].stop)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, candidates[0].policy)
    }

    /**
     * UT-DET-017: Nocturnal wake split behavior (v1 boundary)
     */
    @Test
    fun testUT_DET_017_nocturnalWakeSplitBehavior() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val t2341 = base + (23 * 3600 + 41 * 60) * 1000L
        val t0312 = base + (27 * 3600 + 12 * 60) * 1000L // Aug 21 03:12 -> Gap 1: 3h 31m (12,660,000 ms)
        val t0314 = base + (27 * 3600 + 14 * 60) * 1000L // Aug 21 03:14 -> Gap 2: 2m (< 3h)
        val t0748 = base + (31 * 3600 + 48 * 60) * 1000L // Aug 21 07:48 -> Gap 3: 4h 34m (16,440,000 ms)
        val now = base + 36 * 3600 * 1000L

        val unlocks = listOf(t2341, t0312, t0314, t0748)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)

        assertEquals(1, candidates.size)
        // v1 does not merge split nocturnal intervals; it selects the longest eligible segment
        assertEquals(t0314, candidates[0].start)
        assertEquals(t0748, candidates[0].stop)
        assertEquals((4 * 3600 + 34 * 60) * 1000L, candidates[0].durationMs)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, candidates[0].policy)
    }
}
