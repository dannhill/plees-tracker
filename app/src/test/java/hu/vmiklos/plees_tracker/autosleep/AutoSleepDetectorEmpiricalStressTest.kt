/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import java.util.Calendar
import java.util.Random
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Empirical adversarial challenge & stress test suite for AutoSleepDetector.kt.
 * Tests high-density sequences, rapid jitter, out-of-order streams, extreme boundary values,
 * worldwide timezones, DST shifts, calendar rollover transitions, and randomized fuzzing.
 */
class AutoSleepDetectorEmpiricalStressTest {

    private lateinit var detector: AutoSleepDetector

    @Before
    fun setUp() {
        detector = AutoSleepDetector()
    }

    // =========================================================================
    // 1. Adversarial Timestamp Sequences & Density
    // =========================================================================

    @Test
    fun test_Adv_Dense_01_massiveDenseSequence10k() {
        val baseTime = 1700000000000L
        val count = 10_000
        val unlocks = (0 until count).map { i -> baseTime + (i * 1000L) }
        val now = baseTime + (count * 1000L) + 5000L
        val tz = TimeZone.getTimeZone("UTC")

        val startTime = System.currentTimeMillis()
        val gaps = detector.normalizeUnlocks(unlocks, now)
        val elapsedNorm = System.currentTimeMillis() - startTime

        assertEquals(count - 1, gaps.size)
        assertTrue("Normalization of 10k timestamps must complete under 500ms (took ${elapsedNorm}ms)", elapsedNorm < 500)

        // Overnight policy: every gap is 1000ms (< 3h) -> must return 0 candidates
        val overnight = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("Overnight detector must reject all 1s gaps", overnight.isEmpty())

        // Pure longest: all gaps are 1s -> tie-breaker selects the last one
        val pure = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pure.size)
        assertEquals(1000L, pure[0].durationMs)
        assertEquals(unlocks[count - 1], pure[0].stop)
        assertEquals(unlocks[count - 2], pure[0].start)
    }

    @Test
    fun test_Adv_Dense_02_microsecondBurstDuplicates() {
        val baseTime = 1700000000000L
        val now = baseTime + 1000000L
        val unlocks = mutableListOf<Long>()

        // 50 duplicate timestamps for each step
        for (i in 0 until 50) {
            val t = baseTime + i * 1000L
            repeat(40) {
                unlocks.add(t)
            }
        }
        assertEquals(2000, unlocks.size)

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertEquals(49, gaps.size)
        for (gap in gaps) {
            assertEquals(1000L, gap.durationMs)
            assertTrue(gap.stop > gap.start)
        }
    }

    @Test
    fun test_Adv_Dense_03_highFrequencyUnlockJitterWithOvernightGap() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 18, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tEvening = cal.timeInMillis
        val unlocks = mutableListOf<Long>()

        // 500 daytime jitter unlocks (10s intervals) from 18:00 to 19:23
        for (i in 0 until 500) {
            unlocks.add(tEvening + (i * 10_000L))
        }

        // Sleep onset at 23:00 (Aug 20)
        cal.set(2026, Calendar.AUGUST, 20, 23, 0, 0)
        val tSleepStart = cal.timeInMillis
        unlocks.add(tSleepStart)

        // Wake at 07:00 (Aug 21) -> 8h gap
        cal.set(2026, Calendar.AUGUST, 21, 7, 0, 0)
        val tSleepStop = cal.timeInMillis
        unlocks.add(tSleepStop)

        // 500 morning jitter unlocks from 07:00 onwards
        for (i in 1..500) {
            unlocks.add(tSleepStop + (i * 10_000L))
        }

        val now = tSleepStop + (600 * 10_000L)

        val overnight = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, overnight.size)
        assertEquals(tSleepStart, overnight[0].start)
        assertEquals(tSleepStop, overnight[0].stop)
        assertEquals(8 * 3600 * 1000L, overnight[0].durationMs)

        val pure = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pure.size)
        assertEquals(tSleepStart, pure[0].start)
        assertEquals(tSleepStop, pure[0].stop)
    }

    // =========================================================================
    // 2. Out-of-Order & Adversarial Permutations
    // =========================================================================

    @Test
    fun test_Adv_Order_01_reverseChronological() {
        val baseTime = 1700000000000L
        val now = baseTime + 2_000_000L
        val count = 1000
        val reverseUnlocks = (count downTo 1).map { i -> baseTime + (i * 1000L) }

        val gaps = detector.normalizeUnlocks(reverseUnlocks, now)
        assertEquals(count - 1, gaps.size)
        for (i in gaps.indices) {
            assertTrue(gaps[i].stop > gaps[i].start)
            if (i > 0) {
                assertEquals(gaps[i - 1].stop, gaps[i].start)
            }
        }
    }

    @Test
    fun test_Adv_Order_02_randomlyShuffled() {
        val baseTime = 1700000000000L
        val now = baseTime + 5_000_000L
        val count = 2000
        val ordered = (1..count).map { i -> baseTime + (i * 1000L) }
        val rng = Random(1337)
        val shuffled = ordered.shuffled(rng)

        val gaps = detector.normalizeUnlocks(shuffled, now)
        assertEquals(count - 1, gaps.size)
        for (i in gaps.indices) {
            assertEquals(ordered[i], gaps[i].start)
            assertEquals(ordered[i + 1], gaps[i].stop)
        }
    }

    // =========================================================================
    // 3. Extreme Values, Negative, Zero, and Far Future
    // =========================================================================

    @Test
    fun test_Adv_Extreme_01_minMaxLongValues() {
        val now = 1000000L
        val extremeList = listOf(
            Long.MIN_VALUE,
            -9223372036854775807L,
            -1000000L,
            -1L,
            0L,
            100L,
            500L,
            now,
            now + 1L,
            now + 1000000L,
            Long.MAX_VALUE
        )

        val gaps = detector.normalizeUnlocks(extremeList, now)
        assertEquals(2, gaps.size)
        assertEquals(100L, gaps[0].start)
        assertEquals(500L, gaps[0].stop)
        assertEquals(500L, gaps[1].start)
        assertEquals(now, gaps[1].stop)
    }

    @Test
    fun test_Adv_Extreme_02_farFutureTimestampsPruned() {
        val tz = TimeZone.getTimeZone("UTC")
        val now = 1000000L
        val unlocks = listOf(2000000L, 2000000L + (8 * 3600 * 1000L))

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue(gaps.isEmpty())

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun test_Adv_Extreme_03_farFutureYear3000() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(3000, Calendar.JANUARY, 1, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(3000, Calendar.JANUARY, 2, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(start, candidates[0].start)
        assertEquals(stop, candidates[0].stop)
        assertEquals(8 * 3600 * 1000L, candidates[0].durationMs)
    }

    // =========================================================================
    // 4. DST Transitions Across Multiple Worldwide Timezones
    // =========================================================================

    @Test
    fun test_Adv_DST_01_EuropeRome_SpringForward() {
        val romeTz = TimeZone.getTimeZone("Europe/Rome")
        // March 29, 2026: 02:00 -> 03:00 (+1h)
        val cal = Calendar.getInstance(romeTz).apply {
            set(2026, Calendar.MARCH, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.MARCH, 29, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val expectedDurationMs = 7 * 3600 * 1000L
        assertEquals(expectedDurationMs, stop - start)

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, romeTz)
        assertEquals(1, candidates.size)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_DST_02_EuropeRome_FallBack() {
        val romeTz = TimeZone.getTimeZone("Europe/Rome")
        // October 25, 2026: 03:00 -> 02:00 (-1h)
        val cal = Calendar.getInstance(romeTz).apply {
            set(2026, Calendar.OCTOBER, 24, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.OCTOBER, 25, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val expectedDurationMs = 9 * 3600 * 1000L
        assertEquals(expectedDurationMs, stop - start)

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, romeTz)
        assertEquals(1, candidates.size)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_DST_03_AmericaNewYork_SpringForward() {
        val nyTz = TimeZone.getTimeZone("America/New_York")
        // March 8, 2026: EST to EDT (02:00 -> 03:00)
        val cal = Calendar.getInstance(nyTz).apply {
            set(2026, Calendar.MARCH, 7, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.MARCH, 8, 7, 30, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val expectedDurationMs = 7 * 3600 * 1000L
        assertEquals(expectedDurationMs, stop - start)

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, nyTz)
        assertEquals(1, candidates.size)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_DST_04_AmericaNewYork_FallBack() {
        val nyTz = TimeZone.getTimeZone("America/New_York")
        // November 1, 2026: EDT to EST (02:00 -> 01:00)
        val cal = Calendar.getInstance(nyTz).apply {
            set(2026, Calendar.OCTOBER, 31, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.NOVEMBER, 1, 7, 30, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val expectedDurationMs = 9 * 3600 * 1000L
        assertEquals(expectedDurationMs, stop - start)

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, nyTz)
        assertEquals(1, candidates.size)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_DST_05_AustraliaSydney_SpringForward() {
        val sydneyTz = TimeZone.getTimeZone("Australia/Sydney")
        // October 4, 2026: AEST to AEDT (02:00 -> 03:00)
        val cal = Calendar.getInstance(sydneyTz).apply {
            set(2026, Calendar.OCTOBER, 3, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.OCTOBER, 4, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val expectedDurationMs = 7 * 3600 * 1000L
        assertEquals(expectedDurationMs, stop - start)

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, sydneyTz)
        assertEquals(1, candidates.size)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_DST_06_AustraliaSydney_FallBack() {
        val sydneyTz = TimeZone.getTimeZone("Australia/Sydney")
        // April 5, 2026: AEDT to AEST (03:00 -> 02:00)
        val cal = Calendar.getInstance(sydneyTz).apply {
            set(2026, Calendar.APRIL, 4, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.APRIL, 5, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val expectedDurationMs = 9 * 3600 * 1000L
        assertEquals(expectedDurationMs, stop - start)

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, sydneyTz)
        assertEquals(1, candidates.size)
        assertEquals(expectedDurationMs, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Timezone_07_AsiaKolkata_HalfHourOffset() {
        val kolkataTz = TimeZone.getTimeZone("Asia/Kolkata") // UTC+5:30
        val cal = Calendar.getInstance(kolkataTz).apply {
            set(2026, Calendar.AUGUST, 20, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 6, 30, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, kolkataTz)
        assertEquals(1, candidates.size)
        assertEquals(7 * 3600 * 1000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Timezone_08_AustraliaAdelaide_HalfHourDST() {
        val adelaideTz = TimeZone.getTimeZone("Australia/Adelaide") // UTC+9:30 / +10:30
        val cal = Calendar.getInstance(adelaideTz).apply {
            set(2026, Calendar.JANUARY, 15, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.JANUARY, 16, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, adelaideTz)
        assertEquals(1, candidates.size)
        assertEquals(8 * 3600 * 1000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Timezone_09_PacificChatham_45MinOffset() {
        val chathamTz = TimeZone.getTimeZone("Pacific/Chatham") // UTC+12:45 / +13:45
        val cal = Calendar.getInstance(chathamTz).apply {
            set(2026, Calendar.JUNE, 10, 22, 45, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.JUNE, 11, 6, 45, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, chathamTz)
        assertEquals(1, candidates.size)
        assertEquals(8 * 3600 * 1000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Timezone_10_AsiaTokyo_NoDST() {
        val tokyoTz = TimeZone.getTimeZone("Asia/Tokyo") // UTC+9:00
        val cal = Calendar.getInstance(tokyoTz).apply {
            set(2026, Calendar.AUGUST, 20, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tokyoTz)
        assertEquals(1, candidates.size)
        assertEquals(8 * 3600 * 1000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Timezone_11_PacificKiritimati_UTCPlus14() {
        val kiritimatiTz = TimeZone.getTimeZone("Pacific/Kiritimati") // UTC+14:00
        val cal = Calendar.getInstance(kiritimatiTz).apply {
            set(2026, Calendar.AUGUST, 20, 22, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 6, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, kiritimatiTz)
        assertEquals(1, candidates.size)
        assertEquals(8 * 3600 * 1000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Timezone_12_EtcGMTPlus12_UTCMinus12() {
        val tz = TimeZone.getTimeZone("Etc/GMT+12") // UTC-12:00
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 22, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 6, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(8 * 3600 * 1000L, candidates[0].durationMs)
    }

    // =========================================================================
    // 5. Boundary Precision (Duration & Anchor Thresholds)
    // =========================================================================

    @Test
    fun test_Adv_Bound_01_Duration_2h59m59s999ms_Rejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 1, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stop = start + (3 * 3600 * 1000L) - 1L // 10,799,999 ms
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("2h59m59s999ms must be rejected", candidates.isEmpty())
    }

    @Test
    fun test_Adv_Bound_02_Duration_3h00m00s000ms_Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 1, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stop = start + (3 * 3600 * 1000L) // 10,800,000 ms
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(10800000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Bound_03_Duration_3h00m00s001ms_Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 1, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stop = start + (3 * 3600 * 1000L) + 1L // 10,800,001 ms
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(10800001L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Bound_04_Duration_15h59m59s999ms_Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stop = start + (16 * 3600 * 1000L) - 1L // 57,599,999 ms
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(57599999L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Bound_05_Duration_16h00m00s000ms_Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stop = start + (16 * 3600 * 1000L) // 57,600,000 ms
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(57600000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Bound_06_Duration_16h00m00s001ms_Rejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stop = start + (16 * 3600 * 1000L) + 1L // 57,600,001 ms
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("16h00m00s001ms must be rejected", candidates.isEmpty())
    }

    @Test
    fun test_Adv_Bound_07_Anchor_GapStop_Exactly_000000000_Rejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 19, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("Gap ending exactly at 00:00:00.000 does not intersect anchor (> anchorStart)", candidates.isEmpty())
    }

    @Test
    fun test_Adv_Bound_08_Anchor_GapStop_000000001_Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 19, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 1)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
    }

    @Test
    fun test_Adv_Bound_09_Anchor_GapStart_Exactly_060000000_Rejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 6, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stop = start + 4 * 3600 * 1000L // 10:00
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("Gap starting exactly at 06:00:00.000 does not intersect anchor (< anchorEnd)", candidates.isEmpty())
    }

    @Test
    fun test_Adv_Bound_10_Anchor_GapStart_055959999_Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 5, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val start = cal.timeInMillis
        val stop = start + 4 * 3600 * 1000L // 09:59:59.999
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
    }

    // =========================================================================
    // 6. Calendar & Date Transition Boundaries
    // =========================================================================

    @Test
    fun test_Adv_Cal_01_NewYearTransition() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.DECEMBER, 31, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2027, Calendar.JANUARY, 1, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(start, candidates[0].start)
        assertEquals(stop, candidates[0].stop)
    }

    @Test
    fun test_Adv_Cal_02_LeapYearFeb28To29() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2024, Calendar.FEBRUARY, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2024, Calendar.FEBRUARY, 29, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(start, candidates[0].start)
        assertEquals(stop, candidates[0].stop)
    }

    @Test
    fun test_Adv_Cal_03_NonLeapYearFeb28ToMar1() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.FEBRUARY, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.MARCH, 1, 7, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(start, candidates[0].start)
        assertEquals(stop, candidates[0].stop)
    }

    // =========================================================================
    // 7. Tie-Breaking & Multi-Session Competition
    // =========================================================================

    @Test
    fun test_Adv_Tie_01_EqualDuration_SameWakeDate() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val g1Start = base + 0 * 3600 * 1000L // 00:00
        val g1Stop = base + 4 * 3600 * 1000L  // 04:00 (4h)
        val g2Stop = base + 8 * 3600 * 1000L  // 08:00 (4h gap from 04:00 to 08:00)

        val now = base + 12 * 3600 * 1000L
        val unlocks = listOf(g1Start, g1Stop, g2Stop)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        // Winner must be g2 (04:00 -> 08:00) because g2Stop > g1Stop
        assertEquals(g1Stop, candidates[0].start)
        assertEquals(g2Stop, candidates[0].stop)
        assertEquals(4 * 3600 * 1000L, candidates[0].durationMs)
    }

    @Test
    fun test_Adv_Tie_02_PureLongest_IdenticalGapsAcross72h() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 19, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val unlocks = mutableListOf<Long>()

        // Day 1: 23:00 to 06:00 (7h gap)
        val d1Start = base + 23 * 3600 * 1000L
        val d1Stop = base + 30 * 3600 * 1000L
        unlocks.add(d1Start)
        unlocks.add(d1Stop)

        // Daytime unlocks every 2 hours to avoid long daytime gaps
        for (h in 32..46 step 2) {
            unlocks.add(base + h * 3600 * 1000L)
        }

        // Day 2: 23:00 to 06:00 (7h gap)
        val d2Start = base + 47 * 3600 * 1000L
        val d2Stop = base + 54 * 3600 * 1000L
        unlocks.add(d2Start)
        unlocks.add(d2Stop)

        for (h in 56..70 step 2) {
            unlocks.add(base + h * 3600 * 1000L)
        }

        // Day 3: 23:00 to 06:00 (7h gap)
        val d3Start = base + 71 * 3600 * 1000L
        val d3Stop = base + 78 * 3600 * 1000L
        unlocks.add(d3Start)
        unlocks.add(d3Stop)

        val now = base + 85 * 3600 * 1000L

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals("Tie-breaker across window must select latest stop", d3Start, candidates[0].start)
        assertEquals(d3Stop, candidates[0].stop)
        assertEquals(7 * 3600 * 1000L, candidates[0].durationMs)
    }

    // =========================================================================
    // 8. Randomized Fuzzing Oracle (100 Pseudo-Random Invariant Tests)
    // =========================================================================

    @Test
    fun test_Adv_Fuzz_100RandomSchedules() {
        val rng = Random(4242)
        val tz = TimeZone.getTimeZone("UTC")

        for (trial in 1..100) {
            val baseTime = 1700000000000L + (trial * 100_000_000L)
            val numEvents = rng.nextInt(50) + 5 // 5 to 55 events
            val unlocks = mutableListOf<Long>()
            var current = baseTime

            for (i in 0 until numEvents) {
                // Advance randomly between 1 second and 12 hours
                val step = if (rng.nextBoolean()) {
                    rng.nextInt(60_000).toLong() + 1000L // 1s to 60s
                } else {
                    rng.nextInt(12 * 3600 * 1000).toLong() + 60_000L // 1m to 12h
                }
                current += step
                unlocks.add(current)
            }

            // Shuffle unlocks to verify robustness against ordering
            val shuffled = unlocks.shuffled(rng)
            val now = current + 3600_000L

            // 1. Invariant: normalizeUnlocks produces sorted distinct non-overlapping consecutive intervals
            val gaps = detector.normalizeUnlocks(shuffled, now)
            for (i in gaps.indices) {
                assertTrue("Gap invariant stop > start in trial $trial", gaps[i].stop > gaps[i].start)
                if (i > 0) {
                    assertEquals(gaps[i - 1].stop, gaps[i].start)
                }
            }

            // 2. Invariant: Overnight detection constraints
            val overnightCandidates = detector.detect(shuffled, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
            for (c in overnightCandidates) {
                assertTrue("Candidate stop > start", c.stop > c.start)
                assertEquals(c.stop - c.start, c.durationMs)
                assertTrue("Overnight candidate >= 3h", c.durationMs >= 3 * 3600 * 1000L)
                assertTrue("Overnight candidate <= 16h", c.durationMs <= 16 * 3600 * 1000L)
                assertEquals("Fingerprint format", "1:OVERNIGHT_LONGEST_GAP:${c.start}:${c.stop}", c.fingerprint)
            }

            // Verify candidates sorted by stop descending
            for (i in 0 until overnightCandidates.size - 1) {
                assertTrue(
                    "Candidates must be sorted by stop descending",
                    overnightCandidates[i].stop > overnightCandidates[i + 1].stop
                )
            }

            // 3. Invariant: Pure longest gap candidate
            val pureCandidates = detector.detect(shuffled, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
            if (gaps.isNotEmpty()) {
                assertEquals(1, pureCandidates.size)
                val c = pureCandidates[0]
                assertTrue(c.stop > c.start)
                val maxGap = gaps.maxByOrNull { it.durationMs }!!
                assertEquals("Pure longest must equal max gap duration", maxGap.durationMs, c.durationMs)
            }
        }
    }
}
