/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases E2E Tests for Plees AutoSleep.
 * Exercises extreme limits, invalid inputs, edge thresholds, precision boundary conditions,
 * and capacity bounds (>= 5 tests per feature area).
 */
class AutoSleepTier2BoundaryCornerTest {

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

    // =========================================================================
    // Boundary 1: Empty Inputs & Zero Delimiters
    // =========================================================================

    @Test
    fun test_T2_Empty_01_nullOrEmptyTimestampList() {
        val now = System.currentTimeMillis()
        assertTrue(detector.normalizeUnlocks(emptyList(), now).isEmpty())
        assertTrue(detector.detect(emptyList(), AutoSleepPolicyId.PURE_LONGEST_GAP, now).isEmpty())
        assertTrue(detector.detect(emptyList(), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now).isEmpty())
    }

    @Test
    fun test_T2_Empty_02_allZeroOrNegativeTimestamps() {
        val now = 1000000L
        val unlocks = listOf(0L, -1L, -1000000L, -999999999L)
        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue("Zero and negative timestamps must be completely filtered", gaps.isEmpty())
    }

    @Test
    fun test_T2_Empty_03_allIdenticalTimestamps() {
        val now = 1000000L
        val unlocks = listOf(500000L, 500000L, 500000L, 500000L)
        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue("List with only identical timestamps must produce 0 gaps", gaps.isEmpty())
    }

    @Test
    fun test_T2_Empty_04_exactlyOneValidTimestamp() {
        val now = 1000000L
        val unlocks = listOf(-50L, 500000L) // 1 negative, 1 valid
        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue("Single valid timestamp has no interval; must produce 0 gaps", gaps.isEmpty())
    }

    @Test
    fun test_T2_Empty_05_consecutiveZeroIntervals() {
        val now = 2000000L
        val unlocks = listOf(100L, 100L, 200L, 200L, 300L)
        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertEquals(2, gaps.size)
        assertEquals(100L, gaps[0].start)
        assertEquals(200L, gaps[0].stop)
        assertEquals(200L, gaps[1].start)
        assertEquals(300L, gaps[1].stop)
    }

    // =========================================================================
    // Boundary 2: Future Timestamps & Clock Skew
    // =========================================================================

    @Test
    fun test_T2_Future_01_futureTimestampsPruned() {
        val now = 1000000000L
        val unlocks = listOf(900000000L, 950000000L, 1000000001L, 1050000000L) // 2 past, 2 future
        val gaps = detector.normalizeUnlocks(unlocks, now)

        assertEquals(1, gaps.size)
        assertEquals(900000000L, gaps[0].start)
        assertEquals(950000000L, gaps[0].stop)
    }

    @Test
    fun test_T2_Future_02_allTimestampsInFuture() {
        val now = 1000000000L
        val unlocks = listOf(1000000001L, 1000000002L, 2000000000L)
        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue("All timestamps > now must produce 0 gaps", gaps.isEmpty())
    }

    @Test
    fun test_T2_Future_03_timestampExactlyNowAccepted() {
        val now = 1000000000L
        val unlocks = listOf(900000000L, now)
        val gaps = detector.normalizeUnlocks(unlocks, now)

        assertEquals(1, gaps.size)
        assertEquals(900000000L, gaps[0].start)
        assertEquals(now, gaps[0].stop)
    }

    @Test
    fun test_T2_Future_04_mixedPastAndFutureTimestamps() {
        val now = 5000L
        val unlocks = listOf(1000L, 2000L, 6000L, 7000L)
        val gaps = detector.normalizeUnlocks(unlocks, now)

        assertEquals(1, gaps.size)
        assertEquals(1000L, gaps[0].start)
        assertEquals(2000L, gaps[0].stop)
    }

    @Test
    fun test_T2_Future_05_nowTimestampInPast() {
        val now = 500L
        val unlocks = listOf(1000L, 2000L)
        val gaps = detector.normalizeUnlocks(unlocks, now)
        assertTrue(gaps.isEmpty())
    }

    // =========================================================================
    // Boundary 3: Out-of-bound Durations (3h..16h for Overnight Policy)
    // =========================================================================

    @Test
    fun test_T2_Duration_01_exact2h59m59s999msRejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        // 2h 59m 59s 999ms = 10,799,999 ms
        val stop = start + (3 * 3600 * 1000L) - 1L
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("1ms below 3 hours must be rejected by overnight policy", candidates.isEmpty())
    }

    @Test
    fun test_T2_Duration_02_exact3h00m00s000msAccepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        // Exactly 3h = 10,800,000 ms
        val stop = start + (3 * 3600 * 1000L)
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(10800000L, candidates[0].durationMs)
    }

    @Test
    fun test_T2_Duration_03_exact16h00m00s000msAccepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        // Exactly 16h = 57,600,000 ms (Stop at 12:00 next day)
        val stop = start + (16 * 3600 * 1000L)
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(57600000L, candidates[0].durationMs)
    }

    @Test
    fun test_T2_Duration_04_exact16h00m00s001msRejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        // 16h + 1ms = 57,600,001 ms
        val stop = start + (16 * 3600 * 1000L) + 1L
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("1ms above 16 hours must be rejected by overnight policy", candidates.isEmpty())
    }

    @Test
    fun test_T2_Duration_05_extreme72hGapPureVsOvernight() {
        val tz = TimeZone.getTimeZone("UTC")
        val start = 1000000000L
        val stop = start + 72 * 3600 * 1000L
        val now = stop + 1000L

        val overnightCandidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("72h gap exceeds 16h limit; must be rejected by overnight policy", overnightCandidates.isEmpty())

        val pureCandidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.PURE_LONGEST_GAP, now, tz)
        assertEquals(1, pureCandidates.size)
        assertEquals(72 * 3600 * 1000L, pureCandidates[0].durationMs)
    }

    // =========================================================================
    // Boundary 4: Anchor Intersections (00:00–06:00 Local on Wake Date)
    // =========================================================================

    @Test
    fun test_T2_Anchor_01_gapEndingAt000000000Rejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 18, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 0, 0, 0) // Stop exactly at 00:00:00.000
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        // Wake date is Aug 21. Anchor is Aug 21 00:00 to 06:00.
        // Interval [18:00..00:00] does NOT satisfy stop > anchorStart (00:00 > 00:00 is false)
        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("Gap stopping exactly at anchor start does not intersect anchor window", candidates.isEmpty())
    }

    @Test
    fun test_T2_Anchor_02_gapEndingAt000000001Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 19, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 1) // Stop at 00:00:00.001 (5h00m00s001ms duration)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals("Gap stopping 1ms into anchor window intersects anchor", 1, candidates.size)
    }

    @Test
    fun test_T2_Anchor_03_gapStartingAt055959999Accepted() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 5, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val start = cal.timeInMillis
        // 4h duration -> stop at 09:59:59.999
        val stop = start + 4 * 3600 * 1000L
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals("Gap starting 1ms before anchor end intersects anchor", 1, candidates.size)
    }

    @Test
    fun test_T2_Anchor_04_gapStartingAt060000000Rejected() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 21, 6, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        // 4h duration -> stop at 10:00:00.000
        val stop = start + 4 * 3600 * 1000L
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertTrue("Gap starting at anchor end (06:00:00.000) does not intersect anchor", candidates.isEmpty())
    }

    @Test
    fun test_T2_Anchor_05_gapEnclosingEntireAnchorWindow() {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 22, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(2026, Calendar.AUGUST, 21, 8, 0, 0)
        val stop = cal.timeInMillis
        val now = stop + 3600000L

        val candidates = detector.detect(listOf(start, stop), AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now, tz)
        assertEquals(1, candidates.size)
        assertEquals(10 * 3600 * 1000L, candidates[0].durationMs)
    }

    // =========================================================================
    // Boundary 5: Corrupt JSON & Fail-Safe Recovery
    // =========================================================================

    @Test
    fun test_T2_Corrupt_01_corruptPendingJsonReturnsEmpty() {
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, "{corrupt: true, [unclosed").apply()
        val pending = store.getPendingCandidates()
        assertTrue("Corrupted pending JSON must return empty list without crashing", pending.isEmpty())
    }

    @Test
    fun test_T2_Corrupt_02_corruptRejectedJsonReturnsEmpty() {
        prefs.edit().putString(AutoSleepConfig.REJECTED_JSON_KEY, "<<<invalid json>>>").apply()
        assertFalse(store.isRejected("1:OVERNIGHT_LONGEST_GAP:1000:2000"))
    }

    @Test
    fun test_T2_Corrupt_03_jsonMissingRequiredFieldsHandledSafely() {
        // Missing "policy" or "start" in candidate object
        val brokenJson = """[{"stop": 2000, "generatedAt": 3000}]"""
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, brokenJson).apply()

        val pending = store.getPendingCandidates()
        assertTrue(pending.isEmpty())
    }

    @Test
    fun test_T2_Corrupt_04_jsonTypeMismatchHandledSafely() {
        // Start is string instead of long
        val brokenJson = """[{"start": "not_a_number", "stop": 2000, "policy": "PURE_LONGEST_GAP", "generatedAt": 3000}]"""
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, brokenJson).apply()

        val pending = store.getPendingCandidates()
        assertTrue(pending.isEmpty())
    }

    @Test
    fun test_T2_Corrupt_05_recoveryAfterCorruptedStateOnNextWrite() {
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, "CorruptedContent!").apply()
        val candidate = SleepCandidate(1000L, 2000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 3000L)
        val added = store.addPending(candidate)

        assertTrue(added)
        val pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        assertEquals(candidate.fingerprint, pending[0].fingerprint)
    }

    // =========================================================================
    // Boundary 6: 49% vs 50% Overlap Precision
    // =========================================================================

    @Test
    fun test_T2_Overlap_01_exact49PercentNotSuppressed() {
        val backend = AutoSleepBackend(prefs, object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = emptyList<Long>()
        }, store, detector, fakeDao)

        // 8h candidate = 28,800,000 ms
        val start = 1000000000L
        val stop = start + 28800000L
        val candidate = SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop)

        // Exactly 49% overlap = 14,112,000 ms (3.92h)
        val coveredMs = (28800000L * 0.49).toLong()
        val existingSleep = Sleep().apply {
            this.start = candidate.start
            this.stop = candidate.start + coveredMs
        }

        val coverage = backend.calculateOverlapCoverage(candidate, listOf(existingSleep))
        assertEquals(0.49, coverage, 0.0001)
        assertFalse("49.0% overlap must NOT be suppressed", coverage >= AutoSleepConfig.OVERLAP_SUPPRESSION_THRESHOLD)
    }

    @Test
    fun test_T2_Overlap_02_exact49Point999PercentNotSuppressed() {
        val backend = AutoSleepBackend(prefs, object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = emptyList<Long>()
        }, store, detector, fakeDao)

        val start = 1000000000L
        val stop = start + 28800000L
        val candidate = SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop)

        // 14,399,999 ms = 49.9999965%
        val coveredMs = 14399999L
        val existingSleep = Sleep().apply {
            this.start = candidate.start
            this.stop = candidate.start + coveredMs
        }

        val coverage = backend.calculateOverlapCoverage(candidate, listOf(existingSleep))
        assertTrue("49.999% coverage must be strictly less than 0.50", coverage < 0.50)
    }

    @Test
    fun test_T2_Overlap_03_exact50PercentSuppressed() {
        val backend = AutoSleepBackend(prefs, object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = emptyList<Long>()
        }, store, detector, fakeDao)

        val start = 1000000000L
        val stop = start + 28800000L
        val candidate = SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop)

        // Exactly 50.0% overlap = 14,400,000 ms (4.0h)
        val coveredMs = 14400000L
        val existingSleep = Sleep().apply {
            this.start = candidate.start
            this.stop = candidate.start + coveredMs
        }

        val coverage = backend.calculateOverlapCoverage(candidate, listOf(existingSleep))
        assertEquals(0.50, coverage, 0.0001)
        assertTrue("Exact 50.0% overlap MUST be suppressed", coverage >= AutoSleepConfig.OVERLAP_SUPPRESSION_THRESHOLD)
    }

    @Test
    fun test_T2_Overlap_04_exact50Point001PercentSuppressed() {
        val backend = AutoSleepBackend(prefs, object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = emptyList<Long>()
        }, store, detector, fakeDao)

        val start = 1000000000L
        val stop = start + 28800000L
        val candidate = SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop)

        // 14,400,001 ms = 50.000003%
        val coveredMs = 14400001L
        val existingSleep = Sleep().apply {
            this.start = candidate.start
            this.stop = candidate.start + coveredMs
        }

        val coverage = backend.calculateOverlapCoverage(candidate, listOf(existingSleep))
        assertTrue("50.001% overlap MUST be suppressed", coverage >= 0.50)
    }

    @Test
    fun test_T2_Overlap_05_overlapStartingBeforeCandidateBoundaryClipping() {
        val backend = AutoSleepBackend(prefs, object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = emptyList<Long>()
        }, store, detector, fakeDao)

        // Candidate 10:00 to 18:00 (8h)
        val start = 10000L
        val stop = 18000L
        val candidate = SleepCandidate(start, stop, AutoSleepPolicyId.PURE_LONGEST_GAP, stop)

        // Existing sleep from 05:00 to 14:00 (9h total, but only 4h inside candidate window 10:00..14:00 = 50%)
        val existingSleep = Sleep().apply {
            this.start = 5000L
            this.stop = 14000L
        }

        val coverage = backend.calculateOverlapCoverage(candidate, listOf(existingSleep))
        assertEquals(4000.0 / 8000.0, coverage, 0.0001)
        assertEquals(0.50, coverage, 0.0001)
    }

    // =========================================================================
    // Boundary 7: 96h TTL Expiry in Candidate Store
    // =========================================================================

    @Test
    fun test_T2_TTL_01_rejected95hAgoNotPurged() {
        val candidate = SleepCandidate(1000L, 2000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 3000L)
        val rejectTime = 1000000000L
        store.markRejected(candidate, rejectTime)

        val queryTime = rejectTime + 95 * 3600 * 1000L
        assertTrue("Record rejected 95h ago must not be purged (<96h TTL)", store.isRejected(candidate.fingerprint, queryTime))
    }

    @Test
    fun test_T2_TTL_02_rejected96hPlus1sPurged() {
        val candidate = SleepCandidate(1000L, 2000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 3000L)
        val rejectTime = 1000000000L
        store.markRejected(candidate, rejectTime)

        val queryTime = rejectTime + (96 * 3600 + 1) * 1000L
        assertFalse("Record rejected >96h ago must be purged by TTL", store.isRejected(candidate.fingerprint, queryTime))
    }

    @Test
    fun test_T2_TTL_03_mixedExpiredAndFreshRejectedRecords() {
        val c1 = SleepCandidate(1000L, 2000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 3000L) // expired
        val c2 = SleepCandidate(3000L, 4000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 5000L) // fresh

        val baseTime = 1000000000L
        store.markRejected(c1, baseTime)
        store.markRejected(c2, baseTime + 50 * 3600 * 1000L)

        val queryTime = baseTime + 97 * 3600 * 1000L
        assertFalse(store.isRejected(c1.fingerprint, queryTime))
        assertTrue(store.isRejected(c2.fingerprint, queryTime))
    }

    @Test
    fun test_T2_TTL_04_markRejectedTriggersLazyTtlPurge() {
        val c1 = SleepCandidate(1000L, 2000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 3000L)
        val c2 = SleepCandidate(3000L, 4000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 5000L)

        val baseTime = 1000000000L
        store.markRejected(c1, baseTime)

        // 100h later, mark c2 rejected
        val newTime = baseTime + 100 * 3600 * 1000L
        store.markRejected(c2, newTime)

        assertFalse(store.isRejected(c1.fingerprint, newTime))
        assertTrue(store.isRejected(c2.fingerprint, newTime))
    }

    @Test
    fun test_T2_TTL_05_dynamicNowParameterInIsRejected() {
        val c = SleepCandidate(1000L, 2000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 3000L)
        val baseTime = 1000000000L
        store.markRejected(c, baseTime)

        assertTrue(store.isRejected(c.fingerprint, baseTime + 1000L))
        assertTrue(store.isRejected(c.fingerprint, baseTime + 95 * 3600 * 1000L))
        assertFalse(store.isRejected(c.fingerprint, baseTime + 97 * 3600 * 1000L))
    }

    // =========================================================================
    // Boundary 8: Capacity Limits (MAX_PENDING=7, MAX_REJECTED=30)
    // =========================================================================

    @Test
    fun test_T2_Capacity_01_adding8PendingRetainsTop7SortedByStopDesc() {
        // Add 8 candidates with stops from 1000 to 8000
        for (i in 1..8) {
            val start = i * 1000L
            val stop = start + 500L
            store.addPending(SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop))
        }

        val pending = store.getPendingCandidates()
        assertEquals("Pending store must be capped at MAX_PENDING = 7", 7, pending.size)
        // Verify top 7 most recent (stops 8500 down to 2500, dropping stop 1500)
        assertEquals(8500L, pending[0].stop)
        assertEquals(2500L, pending[6].stop)
    }

    @Test
    fun test_T2_Capacity_02_adding35RejectedCappedAt30() {
        val now = 1000000000L
        for (i in 1..35) {
            val start = i * 1000L
            val stop = start + 500L
            val candidate = SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop)
            store.markRejected(candidate, now + i * 1000L)
        }

        // Check rejection of most recent vs oldest
        val newestCandidate = SleepCandidate(35000L, 35500L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 35500L)
        val oldestCandidate = SleepCandidate(1000L, 1500L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 1500L)

        assertTrue(store.isRejected(newestCandidate.fingerprint, now + 40000L))
        assertFalse("Oldest candidate beyond MAX_REJECTED = 30 must be evicted", store.isRejected(oldestCandidate.fingerprint, now + 40000L))
    }

    @Test
    fun test_T2_Capacity_03_duplicateCandidateDoesNotIncreaseCount() {
        val c = SleepCandidate(1000L, 2000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 2000L)
        assertTrue(store.addPending(c))
        assertFalse("Adding candidate with duplicate fingerprint must return false", store.addPending(c))
        assertEquals(1, store.getPendingCandidates().size)
    }

    @Test
    fun test_T2_Capacity_04_pendingSortingPreservedAfterEviction() {
        val times = listOf(5000L, 1000L, 8000L, 3000L, 7000L, 2000L, 6000L, 4000L)
        for (t in times) {
            store.addPending(SleepCandidate(t, t + 500L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t))
        }

        val pending = store.getPendingCandidates()
        assertEquals(7, pending.size)
        for (i in 0 until pending.size - 1) {
            assertTrue(pending[i].stop >= pending[i + 1].stop)
        }
    }

    @Test
    fun test_T2_Capacity_05_rejectedCapacityEnforcedAfterTtlPurge() {
        val now = 1000000000L
        // Add 20 expired candidates
        for (i in 1..20) {
            val candidate = SleepCandidate(i * 100L, i * 100L + 50L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 0L)
            store.markRejected(candidate, now - 100 * 3600 * 1000L)
        }
        // Add 25 fresh candidates
        for (i in 1..25) {
            val candidate = SleepCandidate(i * 1000L, i * 1000L + 500L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
            store.markRejected(candidate, now + i * 1000L)
        }

        val queryTime = now + 50000L
        // All 25 fresh candidates should be retained (< 30 cap and not expired)
        for (i in 1..25) {
            val candidate = SleepCandidate(i * 1000L, i * 1000L + 500L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
            assertTrue(store.isRejected(candidate.fingerprint, queryTime))
        }
    }
}
