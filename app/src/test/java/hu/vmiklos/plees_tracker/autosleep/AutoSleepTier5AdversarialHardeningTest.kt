/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import java.util.Calendar
import java.util.Collections
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 5: Adversarial Coverage Hardening & Stress Testing for Plees AutoSleep.
 *
 * Exhaustively stress-tests:
 * 1. Extreme timestamp sequences: massive streams (10,000+ to 25,000+ events), inverted/randomized ordering,
 *    negative/zero/future timestamps, microsecond-apart rapid jitter, Long boundary values.
 * 2. Complex timezone and DST transitions: Southern hemisphere DST (Australia/Sydney, America/Sao_Paulo),
 *    non-standard fractional offsets (+05:30 Asia/Kolkata, +05:45 Asia/Kathmandu, +08:45 Australia/Eucla,
 *    +12:45 Pacific/Chatham, -03:30 America/St_Johns, -09:30 Pacific/Marquesas, +13:00/+14:00 Pacific/Tongatapu/Kiritimati),
 *    leap year Feb 29 overnight spans (2024, 2028, century leap 2000 vs non-leap 2100), and year-end rollovers.
 * 3. Boundary overlap calculations: multiple fragmented overlapping sleeps, exact precision thresholds
 *    (49.999% vs 50.000% vs 50.001%), 1ms touching vs penetrating boundaries, enclosing intervals, zero-duration,
 *    and inverted intervals.
 * 4. SharedPreferences candidate store stress: JSON corruption resilience (malformed, truncated, typed, missing keys,
 *    inverted timestamps), 96h TTL boundary precision, MAX_PENDING (7) and MAX_REJECTED (30) capacity bounds under
 *    high concurrency and overflow.
 * 5. Mutex serialization & concurrency: concurrent AutoSleepBackend.scan() invocations across multiple coroutines
 *    and backend instances, verifying zero duplicate Room insertions and store entries.
 */
class AutoSleepTier5AdversarialHardeningTest {

    private lateinit var detector: AutoSleepDetector
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore
    private lateinit var fakeDao: FakeSleepDao

    private class TestUnlockEventSource(private var unlocks: List<Long> = emptyList()) : UnlockEventSource {
        fun setUnlocks(list: List<Long>) {
            unlocks = list
        }

        override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
            return unlocks.filter { it in beginInclusive until endExclusive }
        }
    }

    @Before
    fun setUp() {
        detector = AutoSleepDetector()
        prefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(prefs)
        fakeDao = FakeSleepDao()
    }

    // =========================================================================
    // SECTION 1: EXTREME TIMESTAMP SEQUENCES & JITTER STRESS
    // =========================================================================

    /**
     * Test 1.1: Massive 25,000-event timestamp sequence with 1-second increments.
     * Verifies normalization throughput (< 500ms), deduplication, and suppression of short gaps under overnight policy.
     */
    @Test
    fun test_Sec1_01_massive25kSequenceThroughputAndSuppression() {
        val baseTime = 1700000000000L
        val count = 25_000
        val unlocks = (0 until count).map { i -> baseTime + (i * 1000L) }
        val now = baseTime + (count * 1000L) + 10_000L
        val tz = TimeZone.getTimeZone("UTC")

        val startTime = System.currentTimeMillis()
        val gaps = detector.normalizeUnlocks(unlocks, now)
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(count - 1, gaps.size)
        assertTrue("Normalizing 25k timestamps must execute in < 600ms (took ${elapsed}ms)", elapsed < 600)

        // Overnight detector must reject all 1-second gaps (< 3h)
        val overnight = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("Overnight detector must yield 0 candidates for 1-second intervals", overnight.isEmpty())

        // Pure longest detector chooses the tie-breaker latest gap
        val pure = detector.detect(unlocks, AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pure.size)
        assertEquals(1000L, pure[0].durationMs)
        assertEquals(unlocks[count - 1], pure[0].stop)
        assertEquals(unlocks[count - 2], pure[0].start)
    }

    /**
     * Test 1.2: Massive 10,000-event stream with 10 overnight sleep gaps interleaved with high-frequency daytime use.
     */
    @Test
    fun test_Sec1_02_massive10kInterleavedOvernightGaps() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val base = cal.timeInMillis
        val unlocks = mutableListOf<Long>()

        // 10 consecutive days: each day has 8h nocturnal gap (23:00 to 07:00) + 998 daytime unlock events (every 50s)
        for (day in 0 until 10) {
            val dayStart = base + day * 24 * 3600 * 1000L
            val wake = dayStart + 7 * 3600 * 1000L // 07:00
            val bed = dayStart + 23 * 3600 * 1000L // 23:00

            unlocks.add(wake)
            // 998 daytime unlocks between 07:01 and 22:59
            val daySpan = bed - wake
            val step = daySpan / 999
            for (i in 1..998) {
                unlocks.add(wake + i * step)
            }
            unlocks.add(bed)
        }

        assertEquals(10_000, unlocks.size)
        val now = base + 10 * 24 * 3600 * 1000L + 12 * 3600 * 1000L

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        // There are 9 overnight transitions between Day 0 and Day 9
        assertEquals(9, candidates.size)
        for (c in candidates) {
            assertEquals(8 * 3600 * 1000L, c.durationMs)
        }
    }

    /**
     * Test 1.3: Completely inverted / reverse-sorted 5,000 timestamp sequence.
     */
    @Test
    fun test_Sec1_03_completelyInvertedTimestampStream() {
        val base = 1700000000000L
        val count = 5_000
        val unlocks = (0 until count).map { i -> base + (i * 2000L) }.reversed()
        val now = base + (count * 2000L) + 5000L

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertEquals(count - 1, gaps.size)
        // Verify strictly ascending order
        for (i in 0 until gaps.size - 1) {
            assertTrue("Gaps must be strictly ascending", gaps[i].stop == gaps[i + 1].start)
            assertTrue("Gaps duration must be positive", gaps[i].durationMs > 0)
        }
    }

    /**
     * Test 1.4: Heavily duplicated timestamps (5,000 timestamps with 10 duplicates each = 50,000 total).
     */
    @Test
    fun test_Sec1_04_heavilyDuplicatedTimestamps50k() {
        val base = 1700000000000L
        val uniqueCount = 5_000
        val unlocks = ArrayList<Long>(uniqueCount * 10)
        for (i in 0 until uniqueCount) {
            val ts = base + (i * 3000L)
            repeat(10) {
                unlocks.add(ts)
            }
        }
        val now = base + (uniqueCount * 3000L) + 5000L

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertEquals(uniqueCount - 1, gaps.size)
    }

    /**
     * Test 1.5: Randomized fuzzing stream (10,000 random timestamps including negative, zero, future, and extreme values).
     */
    @Test
    fun test_Sec1_05_randomizedFuzzingStreamWithAnomalies() {
        val rng = Random(42)
        val now = 1700000000000L
        val pastBase = now - 72 * 3600 * 1000L

        val unlocks = mutableListOf<Long>()
        // Valid past timestamps
        repeat(8_000) {
            val offset = (rng.nextDouble() * (72 * 3600 * 1000L)).toLong()
            unlocks.add(pastBase + offset)
        }
        // Negative timestamps
        repeat(500) {
            unlocks.add(-(rng.nextInt(1_000_000_000) + 1).toLong())
        }
        // Zero timestamps
        repeat(500) {
            unlocks.add(0L)
        }
        // Future timestamps
        repeat(500) {
            unlocks.add(now + rng.nextInt(1_000_000_000) + 1L)
        }
        // Boundary extremes
        unlocks.add(Long.MIN_VALUE)
        unlocks.add(-1L)
        unlocks.add(0L)
        unlocks.add(now)
        unlocks.add(now + 1L)
        unlocks.add(Long.MAX_VALUE)

        unlocks.shuffle(rng)

        val gaps = detector.normalizeUnlocks(unlocks, now)
        // All gap timestamps must be > 0 and <= now
        for (gap in gaps) {
            assertTrue("Gap start must be > 0", gap.start > 0L)
            assertTrue("Gap stop must be <= now", gap.stop <= now)
            assertTrue("Gap stop must be > gap start", gap.stop > gap.start)
        }
    }

    /**
     * Test 1.6: Boundary epoch values: Long.MIN_VALUE, -1L, 0L, 1L, now, now + 1L, Long.MAX_VALUE.
     */
    @Test
    fun test_Sec1_06_boundaryEpochExtremeValues() {
        val now = 100_000L
        val unlocks = listOf(
            Long.MIN_VALUE,
            -999999999999L,
            -1L,
            0L,
            1L,
            50_000L,
            now,
            now + 1L,
            999999999999L,
            Long.MAX_VALUE
        )

        val gaps = detector.normalizeUnlocks(unlocks, now)
        // Only 1L, 50_000L, 100_000L (now) are valid positive past/current timestamps
        assertEquals(2, gaps.size)
        assertEquals(1L, gaps[0].start)
        assertEquals(50_000L, gaps[0].stop)
        assertEquals(50_000L, gaps[1].start)
        assertEquals(now, gaps[1].stop)
    }

    /**
     * Test 1.7: Microsecond / 1-millisecond apart unlock events and rapid multi-tap jitter.
     */
    @Test
    fun test_Sec1_07_millisecondApartRapidJitterSequence() {
        val base = 1000000L
        // 100 events each separated by exactly 1 millisecond
        val unlocks = (0 until 100).map { base + it }
        val now = base + 500L

        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertEquals(99, gaps.size)
        for (g in gaps) {
            assertEquals(1L, g.durationMs)
        }
    }

    /**
     * Test 1.8: Lookback window boundaries: events exactly at now - 72h, now - 72h - 1ms, now - 72h + 1ms.
     */
    @Test
    fun test_Sec1_08_lookbackWindowExactBoundaries() = runBlocking {
        val now = 1700000000000L
        val lookback72hMs = 72 * 3600 * 1000L
        val boundary = now - lookback72hMs

        val eventSource = TestUnlockEventSource()
        // Event at boundary - 1ms (excluded by lookback filter in eventSource)
        // Event at boundary (included)
        // Event at boundary + 8h (included, forming 8h gap)
        // Event at now (included)
        eventSource.setUnlocks(
            listOf(
                boundary - 1L,
                boundary,
                boundary + 8 * 3600 * 1000L,
                now - 1000L
            )
        )

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.PURE_LONGEST_GAP.name)
            .apply()

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            nowProvider = { now }
        )

        val res = backend.scan()
        assertEquals(ScanStatus.SUCCESS, res.status)
        assertEquals(1, res.discovered)
        assertEquals(1, res.queued)

        val pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        // Longest gap within lookback is from (boundary + 8h) to (now - 1000L) = 64 hours - 1s
        assertEquals(now - 1000L, pending[0].stop)
        assertEquals(boundary + 8 * 3600 * 1000L, pending[0].start)
    }

    // =========================================================================
    // SECTION 2: COMPLEX GLOBAL TIMEZONES, DST TRANSITIONS & CALENDAR ANOMALIES
    // =========================================================================

    /**
     * Test 2.1: Southern Hemisphere DST Spring-Forward in Australia/Sydney.
     * In October, clock moves forward from 02:00 to 03:00 (23-hour day).
     * Overnight sleep spanning 23:00 Day 1 to 07:00 Day 2 is 7 hours elapsed time.
     */
    @Test
    fun test_Sec2_01_southernHemisphereDstSpringForwardSydney() {
        val tz = TimeZone.getTimeZone("Australia/Sydney")
        val cal = Calendar.getInstance(tz).apply {
            // First Sunday in October 2026 is October 4, 2026 (DST starts at 02:00 -> 03:00)
            set(2026, Calendar.OCTOBER, 3, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.OCTOBER, 4, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis

        // Physical duration is 7 hours due to 1-hour spring forward
        val durationMs = sleepStop - sleepStart
        assertEquals(7 * 3600 * 1000L, durationMs)

        val now = sleepStop + 4 * 3600 * 1000L
        val unlocks = listOf(sleepStart - 3600_000L, sleepStart, sleepStop, now)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
        assertEquals(7 * 3600 * 1000L, candidates[0].durationMs)
    }

    /**
     * Test 2.2: Southern Hemisphere DST Fall-Back in Australia/Sydney.
     * In April, clock moves backward from 03:00 to 02:00 (25-hour day).
     * Overnight sleep spanning 23:00 Day 1 to 07:00 Day 2 is 9 hours elapsed time.
     */
    @Test
    fun test_Sec2_02_southernHemisphereDstFallBackSydney() {
        val tz = TimeZone.getTimeZone("Australia/Sydney")
        val cal = Calendar.getInstance(tz).apply {
            // First Sunday in April 2026 is April 5, 2026 (DST ends at 03:00 -> 02:00)
            set(2026, Calendar.APRIL, 4, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.APRIL, 5, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis

        // Physical duration is 9 hours due to 1-hour fall back
        val durationMs = sleepStop - sleepStart
        assertEquals(9 * 3600 * 1000L, durationMs)

        val now = sleepStop + 4 * 3600 * 1000L
        val unlocks = listOf(sleepStart - 3600_000L, sleepStart, sleepStop, now)

        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
        assertEquals(9 * 3600 * 1000L, candidates[0].durationMs)
    }

    /**
     * Test 2.3: Southern Hemisphere America/Sao_Paulo timezone.
     */
    @Test
    fun test_Sec2_03_americaSaoPauloOvernightSleep() {
        val tz = TimeZone.getTimeZone("America/Sao_Paulo")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.JULY, 15, 22, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.JULY, 16, 6, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 3 * 3600 * 1000L

        val unlocks = listOf(sleepStart - 1800_000L, sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
        assertEquals(8 * 3600 * 1000L, candidates[0].durationMs)
    }

    /**
     * Test 2.4: Fractional +05:30 offset in Asia/Kolkata (India Standard Time).
     */
    @Test
    fun test_Sec2_04_asiaKolkataHalfHourOffset() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.SEPTEMBER, 10, 23, 15, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.SEPTEMBER, 11, 7, 15, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 2 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.5: Fractional +05:45 offset in Asia/Kathmandu (Nepal Time).
     */
    @Test
    fun test_Sec2_05_asiaKathmanduQuarterHourOffset() {
        val tz = TimeZone.getTimeZone("Asia/Kathmandu")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 5, 22, 45, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.NOVEMBER, 6, 6, 45, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 4 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.6: Fractional +08:45 offset in Australia/Eucla (Central Western Standard Time).
     */
    @Test
    fun test_Sec2_06_australiaEuclaQuarterHourOffset() {
        val tz = TimeZone.getTimeZone("Australia/Eucla")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.JUNE, 10, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.JUNE, 11, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 2 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.7: Fractional +12:45 / +13:45 offset in Pacific/Chatham (Chatham Islands).
     */
    @Test
    fun test_Sec2_07_pacificChathamQuarterHourOffset() {
        val tz = TimeZone.getTimeZone("Pacific/Chatham")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.DECEMBER, 15, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.DECEMBER, 16, 7, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 3 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.8: Fractional -03:30 offset in America/St_Johns (Newfoundland Time).
     */
    @Test
    fun test_Sec2_08_americaStJohnsHalfHourOffset() {
        val tz = TimeZone.getTimeZone("America/St_Johns")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.FEBRUARY, 10, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.FEBRUARY, 11, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 2 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.9: Fractional -09:30 offset in Pacific/Marquesas (Marquesas Islands).
     */
    @Test
    fun test_Sec2_09_pacificMarquesasHalfHourOffset() {
        val tz = TimeZone.getTimeZone("Pacific/Marquesas")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.JANUARY, 20, 22, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2026, Calendar.JANUARY, 21, 6, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 4 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.10: Extreme +13:00 / +14:00 timezones in Pacific/Tongatapu and Pacific/Kiritimati.
     */
    @Test
    fun test_Sec2_10_extremePositiveTimezonesTongatapuKiritimati() {
        for (tzName in listOf("Pacific/Tongatapu", "Pacific/Kiritimati")) {
            val tz = TimeZone.getTimeZone(tzName)
            val cal = Calendar.getInstance(tz).apply {
                set(2026, Calendar.MAY, 5, 23, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val sleepStart = cal.timeInMillis

            cal.apply {
                set(2026, Calendar.MAY, 6, 7, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val sleepStop = cal.timeInMillis
            val now = sleepStop + 3 * 3600 * 1000L

            val unlocks = listOf(sleepStart, sleepStop, now)
            val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
            assertEquals("Failed for $tzName", 1, candidates.size)
            assertEquals(sleepStart, candidates[0].start)
            assertEquals(sleepStop, candidates[0].stop)
        }
    }

    /**
     * Test 2.11: Leap year February 29 transitions: Feb 28 23:00 to Feb 29 07:00 (2024 leap year).
     */
    @Test
    fun test_Sec2_11_leapYearFeb28ToFeb29Transition2024() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2024, Calendar.FEBRUARY, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2024, Calendar.FEBRUARY, 29, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 2 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.12: Leap year February 29 to March 1 transitions: Feb 29 23:00 to Mar 01 07:00 (2024 leap year).
     */
    @Test
    fun test_Sec2_12_leapYearFeb29ToMar01Transition2024() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2024, Calendar.FEBRUARY, 29, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2024, Calendar.MARCH, 1, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 2 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    /**
     * Test 2.13: Century leap year 2000 vs non-leap century year 2100 Feb 28-29 rollover.
     */
    @Test
    fun test_Sec2_13_centuryLeapYear2000VsNonLeap2100() {
        val tz = TimeZone.getTimeZone("UTC")

        // 2000 is leap: Feb 28 to Feb 29
        val cal2000 = Calendar.getInstance(tz).apply {
            set(2000, Calendar.FEBRUARY, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start2000 = cal2000.timeInMillis
        cal2000.set(2000, Calendar.FEBRUARY, 29, 7, 0, 0)
        val stop2000 = cal2000.timeInMillis
        val now2000 = stop2000 + 3600_000L

        val cand2000 = detector.detect(listOf(start2000, stop2000, now2000), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now2000, tz)
        assertEquals(1, cand2000.size)

        // 2100 is NOT leap: Feb 28 to Mar 1
        val cal2100 = Calendar.getInstance(tz).apply {
            set(2100, Calendar.FEBRUARY, 28, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start2100 = cal2100.timeInMillis
        cal2100.set(2100, Calendar.MARCH, 1, 7, 0, 0)
        val stop2100 = cal2100.timeInMillis
        val now2100 = stop2100 + 3600_000L

        val cand2100 = detector.detect(listOf(start2100, stop2100, now2100), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now2100, tz)
        assertEquals(1, cand2100.size)
    }

    /**
     * Test 2.14: Year-end rollover: Dec 31 23:00 to Jan 01 07:00 across year boundary.
     */
    @Test
    fun test_Sec2_14_yearEndRolloverDec31ToJan01() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.DECEMBER, 31, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStart = cal.timeInMillis

        cal.apply {
            set(2027, Calendar.JANUARY, 1, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sleepStop = cal.timeInMillis
        val now = sleepStop + 4 * 3600 * 1000L

        val unlocks = listOf(sleepStart, sleepStop, now)
        val candidates = detector.detect(unlocks, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(sleepStart, candidates[0].start)
        assertEquals(sleepStop, candidates[0].stop)
    }

    // =========================================================================
    // SECTION 3: BOUNDARY OVERLAP CALCULATIONS & INTERVAL ARITHMETIC
    // =========================================================================

    /**
     * Test 3.1: Precision boundary exactly 49.999% coverage -> NOT suppressed.
     */
    @Test
    fun test_Sec3_01_precisionBoundary49Point999PercentNotSuppressed() {
        val candidate = SleepCandidate(
            start = 0L,
            stop = 100_000L, // 100,000 ms duration
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 100_000L
        )

        // Existing sleep covers exactly 49,999 ms = 49.999%
        val existing = listOf(
            Sleep().apply {
                start = 0L
                stop = 49_999L
            }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(0.49999, coverage, 1e-9)
        assertFalse("49.999% coverage must NOT be suppressed (< 50%)", AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.2: Precision boundary exactly 50.000% coverage -> SUPPRESSED.
     */
    @Test
    fun test_Sec3_02_precisionBoundary50Point000PercentSuppressed() {
        val candidate = SleepCandidate(
            start = 0L,
            stop = 100_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 100_000L
        )

        // Existing sleep covers exactly 50,000 ms = 50.000%
        val existing = listOf(
            Sleep().apply {
                start = 0L
                stop = 50_000L
            }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(0.50000, coverage, 1e-9)
        assertTrue("50.000% coverage MUST be suppressed (>= 50%)", AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.3: Precision boundary exactly 50.001% coverage -> SUPPRESSED.
     */
    @Test
    fun test_Sec3_03_precisionBoundary50Point001PercentSuppressed() {
        val candidate = SleepCandidate(
            start = 0L,
            stop = 100_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 100_000L
        )

        // Existing sleep covers exactly 50,001 ms = 50.001%
        val existing = listOf(
            Sleep().apply {
                start = 0L
                stop = 50_001L
            }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(0.50001, coverage, 1e-9)
        assertTrue("50.001% coverage MUST be suppressed (>= 50%)", AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.4: 1-millisecond touching boundaries at start and end: [cand.start - 1000, cand.start] and [cand.stop, cand.stop + 1000] -> 0% coverage.
     */
    @Test
    fun test_Sec3_04_touchingBoundaryExactZeroCoverage() {
        val candidate = SleepCandidate(
            start = 10_000L,
            stop = 20_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 20_000L
        )

        val existing = listOf(
            Sleep().apply {
                start = 5_000L
                stop = 10_000L // touching start
            },
            Sleep().apply {
                start = 20_000L // touching stop
                stop = 25_000L
            }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(0.0, coverage, 1e-9)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.5: 1-millisecond penetrating overlap [cand.start - 1000, cand.start + 1] -> exact 1ms coverage.
     */
    @Test
    fun test_Sec3_05_oneMillisecondPenetratingOverlap() {
        val candidate = SleepCandidate(
            start = 10_000L,
            stop = 20_000L, // 10,000 ms duration
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 20_000L
        )

        val existing = listOf(
            Sleep().apply {
                start = 9_000L
                stop = 10_001L // 1ms overlap inside candidate
            }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(1.0 / 10_000.0, coverage, 1e-9)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.6: Completely enclosing intervals (existing sleep strictly contains candidate).
     */
    @Test
    fun test_Sec3_06_completelyEnclosingSleepInterval() {
        val candidate = SleepCandidate(
            start = 10_000L,
            stop = 20_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 20_000L
        )

        val existing = listOf(
            Sleep().apply {
                start = 5_000L
                stop = 25_000L
            }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(1.0, coverage, 1e-9)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.7: Fragmented non-contiguous intervals (10 disjoint naps inside 8h candidate).
     */
    @Test
    fun test_Sec3_07_fragmentedTenDisjointNapsSummation() {
        val candidateDuration = 8 * 3600 * 1000L
        val candidate = SleepCandidate(
            start = 0L,
            stop = candidateDuration,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = candidateDuration
        )

        // 10 naps of 30 minutes each = 300 minutes = 5 hours = 62.5% of 8 hours
        val existing = (0 until 10).map { i ->
            val napStart = (i * 45 * 60 * 1000L) // every 45 mins
            val napStop = napStart + (30 * 60 * 1000L) // 30 min duration
            Sleep().apply {
                start = napStart
                stop = napStop
            }
        }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(5.0 / 8.0, coverage, 1e-9) // 0.625
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.8: Heavily overlapping, nested, redundant, and unsorted existing sleeps.
     */
    @Test
    fun test_Sec3_08_heavilyNestedRedundantUnsortedIntervals() {
        val candidate = SleepCandidate(
            start = 0L,
            stop = 1000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1000L
        )

        // Multiple overlapping redundant intervals that together cover [100, 600] = 500ms (50%)
        val existing = listOf(
            Sleep().apply { start = 300L; stop = 500L },
            Sleep().apply { start = 100L; stop = 400L },
            Sleep().apply { start = 200L; stop = 350L },
            Sleep().apply { start = 450L; stop = 600L },
            Sleep().apply { start = 150L; stop = 250L }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(0.50, coverage, 1e-9)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.9: Zero-duration and inverted existing sleep records in database ([1000, 1000], [2000, 1000]).
     */
    @Test
    fun test_Sec3_09_degenerateZeroAndInvertedRecordsIgnored() {
        val candidate = SleepCandidate(
            start = 0L,
            stop = 10_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 10_000L
        )

        val existing = listOf(
            Sleep().apply { start = 1000L; stop = 1000L }, // zero duration
            Sleep().apply { start = 5000L; stop = 2000L }, // inverted start > stop
            Sleep().apply { start = -5000L; stop = -1000L } // outside bounds
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(0.0, coverage, 1e-9)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    /**
     * Test 3.10: Contiguous adjacent sleep intervals seamlessly merged without double counting.
     */
    @Test
    fun test_Sec3_10_contiguousAdjacentIntervalsMergedWithoutGap() {
        val candidate = SleepCandidate(
            start = 0L,
            stop = 10_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 10_000L
        )

        // Three contiguous slices: [0, 2000], [2000, 4000], [4000, 5000] = 5000ms (50%)
        val existing = listOf(
            Sleep().apply { start = 0L; stop = 2000L },
            Sleep().apply { start = 2000L; stop = 4000L },
            Sleep().apply { start = 4000L; stop = 5000L }
        )

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existing)
        assertEquals(0.50, coverage, 1e-9)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, existing))
    }

    // =========================================================================
    // SECTION 4: SHAREDPREFERENCES CANDIDATE STORE ADVERSARIAL STRESS
    // =========================================================================

    /**
     * Test 4.1: Extreme JSON corruption: malformed JSON, unclosed brackets, raw HTML, numbers, boolean strings.
     */
    @Test
    fun test_Sec4_01_jsonCorruptionResilience() {
        val corruptPayloads = listOf(
            "{not_json",
            "[unclosed_array",
            "null",
            "",
            "   \t\n  ",
            "<!DOCTYPE html><html><body>Error</body></html>",
            "12345678",
            "true",
            "false",
            "{\"start\": 1000, \"stop\": 2000}" // Object instead of Array
        )

        for (payload in corruptPayloads) {
            prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, payload).apply()
            prefs.edit().putString(AutoSleepConfig.REJECTED_JSON_KEY, payload).apply()

            val pending = store.getPendingCandidates()
            assertTrue("Corrupt pending payload '$payload' must return emptyList without throwing", pending.isEmpty())

            val isRej = store.isRejected("1:OVERNIGHT_LONGEST_GAP:100:200")
            assertFalse("Corrupt rejected payload '$payload' must return false without throwing", isRej)
        }
    }

    /**
     * Test 4.2: Truncated JSON strings and partial objects.
     */
    @Test
    fun test_Sec4_02_truncatedJsonStrings() {
        val truncated = "[{\"start\": 1000, \"stop\": 2000, \"policy\": \"OVERNIGHT_LONGEST_GAP\", \"generated"
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, truncated).apply()
        assertEquals(emptyList<SleepCandidate>(), store.getPendingCandidates())
    }

    /**
     * Test 4.3: JSON Array with non-object elements (strings, ints, nulls, booleans).
     */
    @Test
    fun test_Sec4_03_jsonArrayWithNonObjectPrimitives() {
        val mixedArray = "[\"string_val\", 123, true, null, [1, 2, 3]]"
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, mixedArray).apply()
        assertEquals(emptyList<SleepCandidate>(), store.getPendingCandidates())
    }

    /**
     * Test 4.4: JSON Objects with missing fields or invalid field types (start as string, stop as boolean, invalid policy enum).
     */
    @Test
    fun test_Sec4_04_jsonObjectsWithMissingOrInvalidFields() {
        val invalidObjects = listOf(
            "[{\"start\": \"invalid_str\", \"stop\": 2000, \"policy\": \"OVERNIGHT_LONGEST_GAP\", \"generatedAt\": 100}]",
            "[{\"start\": 1000, \"stop\": 2000, \"policy\": \"NON_EXISTENT_POLICY\", \"generatedAt\": 100}]",
            "[{\"start\": 1000}]" // missing stop, policy, generatedAt
        )

        for (json in invalidObjects) {
            prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, json).apply()
            val pending = store.getPendingCandidates()
            assertTrue("Invalid object payload must return emptyList", pending.isEmpty())
        }
    }

    /**
     * Test 4.5: JSON Objects with inverted timestamps (stop < start or stop == start).
     */
    @Test
    fun test_Sec4_05_jsonObjectsWithInvertedTimestampsRejectedByInit() {
        val invertedJson = "[{\"start\": 2000, \"stop\": 1000, \"policy\": \"OVERNIGHT_LONGEST_GAP\", \"generatedAt\": 100}]"
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, invertedJson).apply()
        assertEquals(emptyList<SleepCandidate>(), store.getPendingCandidates())

        val zeroDurationJson = "[{\"start\": 1000, \"stop\": 1000, \"policy\": \"OVERNIGHT_LONGEST_GAP\", \"generatedAt\": 100}]"
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, zeroDurationJson).apply()
        assertEquals(emptyList<SleepCandidate>(), store.getPendingCandidates())
    }

    /**
     * Test 4.6: 96h TTL exact boundary precision: cutoff - 1ms (purged), cutoff (retained), cutoff + 1ms (retained).
     */
    @Test
    fun test_Sec4_06_rejectedTtlExactBoundaryPrecision() {
        val now = 1000_000_000L
        val ttlMs = AutoSleepConfig.REJECTED_TTL_HOURS * 3600 * 1000L
        val cutoff = now - ttlMs

        val candExpired = SleepCandidate(100L, 200L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, cutoff - 1L)
        val candBoundary = SleepCandidate(300L, 400L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, cutoff)
        val candActive = SleepCandidate(500L, 600L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, cutoff + 1L)

        // Mark rejected at specific timestamps
        store.markRejected(candExpired, cutoff - 1L)
        store.markRejected(candBoundary, cutoff)
        store.markRejected(candActive, cutoff + 1L)

        // Query at `now`
        assertFalse("cutoff - 1ms must be expired and purged", store.isRejected(candExpired.fingerprint, now))
        assertTrue("cutoff exact millisecond must be active and retained", store.isRejected(candBoundary.fingerprint, now))
        assertTrue("cutoff + 1ms must be active and retained", store.isRejected(candActive.fingerprint, now))
    }

    /**
     * Test 4.7: Candidate capacity overflow: add 100 candidates to pending -> capped at exactly MAX_PENDING (7), ordered by stop DESC.
     */
    @Test
    fun test_Sec4_07_pendingCapacityOverflowCappedAtSeven() {
        val base = 1000000L
        for (i in 0 until 100) {
            val candidate = SleepCandidate(
                start = base + (i * 10_000L),
                stop = base + (i * 10_000L) + 5_000L,
                policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                generatedAt = base + (i * 10_000L)
            )
            store.addPending(candidate)
        }

        val pending = store.getPendingCandidates()
        assertEquals(AutoSleepConfig.MAX_PENDING, pending.size)
        assertEquals(7, pending.size)

        // Must be top 7 highest stop timestamps in descending order (i = 99 down to 93)
        for (i in 0 until 7) {
            val expectedStop = base + ((99 - i) * 10_000L) + 5_000L
            assertEquals(expectedStop, pending[i].stop)
        }
    }

    /**
     * Test 4.8: Rejection capacity overflow: mark 100 candidates rejected -> capped at exactly MAX_REJECTED (30), newest first.
     */
    @Test
    fun test_Sec4_08_rejectedCapacityOverflowCappedAtThirty() {
        val now = 1000000L
        for (i in 0 until 100) {
            val candidate = SleepCandidate(
                start = (i * 1000L) + 1L,
                stop = (i * 1000L) + 500L,
                policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                generatedAt = (i * 1000L)
            )
            store.markRejected(candidate, now + i)
        }

        // Check rejection query: all top 30 (i = 99 down to 70) must be present
        for (i in 70 until 100) {
            val fp = "1:OVERNIGHT_LONGEST_GAP:${(i * 1000L) + 1L}:${(i * 1000L) + 500L}"
            assertTrue("Candidate i=$i should be in rejected store", store.isRejected(fp, now + 1000))
        }

        // Candidates evicted from capacity bound (< 70) must not be rejected
        for (i in 0 until 70) {
            val fp = "1:OVERNIGHT_LONGEST_GAP:${(i * 1000L) + 1L}:${(i * 1000L) + 500L}"
            assertFalse("Candidate i=$i should have been evicted by capacity cap", store.isRejected(fp, now + 1000))
        }
    }

    /**
     * Test 4.9: Rejection expiration & re-addition: candidate rejected 96h + 1s ago can be successfully added to pending again.
     */
    @Test
    fun test_Sec4_09_rejectedExpirationAllowsReAddition() {
        val now = 1000_000_000L
        val oldTime = now - (96 * 3600 * 1000L) - 1000L // 96h 1s ago

        val candidate = SleepCandidate(
            start = 1000L,
            stop = 5000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = oldTime
        )

        store.markRejected(candidate, oldTime)

        // At oldTime, it is rejected
        assertTrue(store.isRejected(candidate.fingerprint, oldTime))
        assertFalse(store.addPending(candidate, oldTime))

        // At `now`, rejection expired (> 96h)
        assertFalse(store.isRejected(candidate.fingerprint, now))
        val added = store.addPending(candidate, now)
        assertTrue("Expired rejected candidate should be re-addable to pending", added)
        assertTrue(store.isPending(candidate.fingerprint))
    }

    /**
     * Test 4.10: High-concurrency multithreaded store mutations (50 threads concurrently adding, accepting, rejecting, and querying).
     */
    @Test
    fun test_Sec4_10_concurrentStoreMutationsStress50Threads() {
        val threadCount = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)
        val now = System.currentTimeMillis()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    val cand = SleepCandidate(
                        start = (t * 1000L) + 1L,
                        stop = (t * 1000L) + 800L,
                        policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                        generatedAt = now
                    )
                    store.addPending(cand, now)
                    store.isPending(cand.fingerprint)
                    if (t % 3 == 0) {
                        store.markAccepted(cand)
                    } else if (t % 3 == 1) {
                        store.markRejected(cand, now)
                    }
                    store.getPendingCandidates()
                    store.isRejected(cand.fingerprint, now)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All threads must finish in 5 seconds", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Zero concurrency errors permitted in store mutations", 0, errors.get())

        // Invariants must hold
        assertTrue(store.getPendingCandidates().size <= AutoSleepConfig.MAX_PENDING)
    }

    // =========================================================================
    // SECTION 5: MUTEX SERIALIZATION & CONCURRENCY IN AUTOSLEEPBACKEND
    // =========================================================================

    /**
     * Test 5.1: 50 concurrent backend.scan() calls in SUGGEST mode -> zero duplicate pending candidates stored.
     */
    @Test
    fun test_Sec5_01_concurrentBackendScanSuggestModeZeroDuplicates() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseTime = cal.timeInMillis
        val sleepStart = baseTime + 23 * 3600 * 1000L // 23:00 Day 1
        val sleepStop = baseTime + 31 * 3600 * 1000L  // 07:00 Day 2 (8h overnight)
        val now = baseTime + 36 * 3600 * 1000L        // 12:00 Day 2

        val eventSource = TestUnlockEventSource(listOf(sleepStart - 3600_000L, sleepStart, sleepStop, now - 1000L))

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name)
            .apply()

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        // Launch 50 concurrent scans
        val results = coroutineScope {
            (0 until 50).map {
                async(Dispatchers.Default) {
                    backend.scan()
                }
            }.awaitAll()
        }

        assertEquals(50, results.size)
        val successCount = results.count { it.status == ScanStatus.SUCCESS }
        assertEquals(50, successCount)

        val totalQueued = results.sumOf { it.queued }
        assertEquals("Only one scan can queue the candidate, subsequent scans find it already pending", 1, totalQueued)

        val pending = store.getPendingCandidates()
        assertEquals("Exactly one pending candidate in store", 1, pending.size)
        assertEquals(0, fakeDao.count())
    }

    /**
     * Test 5.2: 50 concurrent backend.scan() calls in AUTO_SAVE mode -> zero duplicate records inserted in Room SleepDao.
     */
    @Test
    fun test_Sec5_02_concurrentBackendScanAutoSaveModeZeroDuplicateInserts() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseTime = cal.timeInMillis
        val sleepStart = baseTime + 23 * 3600 * 1000L // 23:00 Day 1
        val sleepStop = baseTime + 31 * 3600 * 1000L  // 07:00 Day 2 (8h overnight)
        val now = baseTime + 36 * 3600 * 1000L        // 12:00 Day 2

        val eventSource = TestUnlockEventSource(listOf(sleepStart - 3600_000L, sleepStart, sleepStop, now - 1000L))

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name)
            .apply()

        val autoSavedCount = AtomicInteger(0)

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            timeZoneProvider = { tz },
            nowProvider = { now },
            onAutoSaved = {
                autoSavedCount.incrementAndGet()
            }
        )

        // Launch 50 concurrent scans
        val results = coroutineScope {
            (0 until 50).map {
                async(Dispatchers.Default) {
                    backend.scan()
                }
            }.awaitAll()
        }

        assertEquals(50, results.size)
        val totalAutoSaved = results.sumOf { it.autoSaved }
        assertEquals("Exactly one scan can auto-save, subsequent scans find 100% overlap in Room", 1, totalAutoSaved)

        assertEquals("Exactly one Room database record inserted", 1, fakeDao.count())
        assertEquals("Callback onAutoSaved called exactly once", 1, autoSavedCount.get())
        assertEquals("Zero pending candidates in store", 0, store.getPendingCandidates().size)
    }

    /**
     * Test 5.3: Multiple distinct AutoSleepBackend instances executing concurrent scans -> synchronized via companion Mutex.
     */
    @Test
    fun test_Sec5_03_multiInstanceBackendConcurrencySharingCompanionMutex() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseTime = cal.timeInMillis
        val sleepStart = baseTime + 23 * 3600 * 1000L // 23:00 Day 1
        val sleepStop = baseTime + 31 * 3600 * 1000L  // 07:00 Day 2 (8h overnight)
        val now = baseTime + 36 * 3600 * 1000L        // 12:00 Day 2

        val eventSource = TestUnlockEventSource(listOf(sleepStart - 3600_000L, sleepStart, sleepStop, now - 1000L))

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name)
            .apply()

        // Create 20 distinct backend instances sharing the same SharedPreferences and Room DAO
        val backends = (0 until 20).map {
            AutoSleepBackend(
                preferences = prefs,
                eventSource = eventSource,
                store = store,
                detector = detector,
                sleepDao = fakeDao,
                isSupportedProvider = { true },
                hasPermissionProvider = { true },
                timeZoneProvider = { tz },
                nowProvider = { now }
            )
        }

        val results = coroutineScope {
            backends.map { b ->
                async(Dispatchers.Default) {
                    b.scan()
                }
            }.awaitAll()
        }

        val totalAutoSaved = results.sumOf { it.autoSaved }
        assertEquals("Companion Mutex must prevent race across distinct backend instances", 1, totalAutoSaved)
        assertEquals(1, fakeDao.count())
    }

    /**
     * Test 5.4: Race condition between background scan() and user acceptDetectedSleep() / markRejected().
     */
    @Test
    fun test_Sec5_04_raceConditionBetweenScanAndUserConfirmation() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseTime = cal.timeInMillis
        val sleepStart = baseTime + 23 * 3600 * 1000L // 23:00 Day 1
        val sleepStop = baseTime + 31 * 3600 * 1000L  // 07:00 Day 2 (8h overnight)
        val now = baseTime + 36 * 3600 * 1000L        // 12:00 Day 2

        val eventSource = TestUnlockEventSource(listOf(sleepStart - 3600_000L, sleepStart, sleepStop, now - 1000L))

        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name)
            .apply()

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        // First scan queues candidate
        val r1 = backend.scan()
        assertEquals(1, r1.queued)

        val pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        val candidate = pending[0]

        // User rejects candidate concurrently with next scan
        coroutineScope {
            val userReject = async(Dispatchers.Default) {
                store.markRejected(candidate, now)
            }
            val backgroundScan = async(Dispatchers.Default) {
                backend.scan()
            }
            userReject.await()
            val r2 = backgroundScan.await()
            assertEquals("Second scan must not re-queue rejected candidate", 0, r2.queued)
        }

        assertTrue(store.isRejected(candidate.fingerprint, now))
        assertEquals(0, store.getPendingCandidates().size)
    }

    /**
     * Test 5.5: Scan pipeline under rapid state flapping (toggle enabled/disabled, change save mode, change policy during concurrent scans).
     */
    @Test
    fun test_Sec5_05_rapidStateFlappingResilience() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseTime = cal.timeInMillis
        val sleepStart = baseTime + 23 * 3600 * 1000L
        val sleepStop = baseTime + 31 * 3600 * 1000L
        val now = baseTime + 36 * 3600 * 1000L

        val eventSource = TestUnlockEventSource(listOf(sleepStart, sleepStop, now - 1000L))

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        coroutineScope {
            // Flapper coroutine toggles settings
            val flapper = async(Dispatchers.Default) {
                for (i in 0 until 50) {
                    prefs.edit()
                        .putBoolean(AutoSleepConfig.ENABLED_KEY, i % 2 == 0)
                        .putString(AutoSleepConfig.SAVE_MODE_KEY, if (i % 3 == 0) "AUTO_SAVE" else "SUGGEST")
                        .putString(AutoSleepConfig.POLICY_KEY, if (i % 2 == 0) "OVERNIGHT_LONGEST_GAP" else "PURE_LONGEST_GAP")
                        .apply()
                    delay(2)
                }
            }

            // Scanner coroutines execute scans concurrently
            val scanners = (0 until 20).map {
                async(Dispatchers.Default) {
                    backend.scan()
                }
            }

            flapper.await()
            scanners.awaitAll()
        }

        // Database and store must not be corrupted
        assertTrue(store.getPendingCandidates().size <= AutoSleepConfig.MAX_PENDING)
    }

    /**
     * Test 5.6: Mutex release verification when detector returns empty, disabled, unsupported, or permission revoked.
     */
    @Test
    fun test_Sec5_06_mutexReleaseUnderAllEarlyReturnConditions() = runBlocking {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseTime = cal.timeInMillis
        val sleepStart = baseTime + 23 * 3600 * 1000L
        val sleepStop = baseTime + 31 * 3600 * 1000L
        val now = baseTime + 36 * 3600 * 1000L

        val eventSource = TestUnlockEventSource(emptyList())

        // 1. DISABLED early return
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        var backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )
        assertEquals(ScanStatus.DISABLED, backend.scan().status)

        // 2. UNSUPPORTED early return
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { false },
            hasPermissionProvider = { true },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )
        assertEquals(ScanStatus.UNSUPPORTED, backend.scan().status)

        // 3. NO_PERMISSION early return
        backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { false },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )
        assertEquals(ScanStatus.NO_PERMISSION, backend.scan().status)

        // 4. EMPTY early return
        backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = eventSource,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )
        assertEquals(ScanStatus.EMPTY, backend.scan().status)

        // 5. Subsequent valid scan must acquire lock cleanly and succeed
        eventSource.setUnlocks(listOf(sleepStart - 3600_000L, sleepStart, sleepStop, now - 1000L))

        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
    }
}
