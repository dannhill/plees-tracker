/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.content.SharedPreferences
import hu.vmiklos.plees_tracker.Sleep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical challenger stress test suite for AutoSleepCandidateStore and AutoSleepOverlapCalculator.
 * Stress tests:
 * 1. JSON corruption, unexpected types, missing fields, and inverted timestamps.
 * 2. Boundary capacity overflow (>100 items for pending and rejected).
 * 3. TTL expiry arithmetic at 96h boundaries (95h59m, 96h, 96h01m).
 * 4. High-concurrency rapid mutations.
 * 5. Complex, multi-overlap, nested, and unsorted intervals.
 * 6. Precision boundary thresholds (49.999% vs 50.000% vs 50.001%).
 * 7. Contiguous touching intervals and degenerate zero/negative durations.
 */
class AutoSleepEmpiricalStressTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(fakePrefs)
    }

    // =========================================================================
    // SECTION 1: CANDIDATE STORE - JSON CORRUPTION, UNEXPECTED TYPES & MISSING FIELDS
    // =========================================================================

    @Test
    fun testStore_completelyMalformedJson_returnsEmptyAndFalse() {
        val malformedSnippets = listOf(
            "{not_even_json",
            "[unclosed_array",
            "null",
            "",
            "   \n\t ",
            "<html><body>404 Not Found</body></html>",
            "12345678",
            "true",
            "{\"start\": 1000}" // Object instead of Array
        )

        for (snippet in malformedSnippets) {
            fakePrefs.edit()
                .putString(AutoSleepConfig.PENDING_JSON_KEY, snippet)
                .putString(AutoSleepConfig.REJECTED_JSON_KEY, snippet)
                .apply()

            val pending = store.getPendingCandidates()
            assertTrue("Malformed JSON '$snippet' must safely return empty pending list", pending.isEmpty())

            val rejected = store.isRejected("1:OVERNIGHT_LONGEST_GAP:100:200")
            assertFalse("Malformed JSON '$snippet' must safely return false for isRejected", rejected)
        }
    }

    @Test
    fun testStore_jsonArrayWithNonObjects_returnsEmptyList() {
        val nonObjectArrays = listOf(
            "[1, 2, 3, 4]",
            "[\"string1\", \"string2\"]",
            "[true, false, null]",
            "[[100, 200], [300, 400]]"
        )

        for (snippet in nonObjectArrays) {
            fakePrefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, snippet).apply()
            val pending = store.getPendingCandidates()
            assertTrue("JSON array of non-objects must safely return empty list", pending.isEmpty())
        }
    }

    @Test
    fun testStore_jsonWithUnexpectedFieldTypes_returnsEmptyList() {
        val badTypeObjects = listOf(
            // start is boolean
            """[{"start": true, "stop": 200, "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": 300}]""",
            // stop is string containing letters
            """[{"start": 100, "stop": "invalid_stop", "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": 300}]""",
            // policy is an unknown enum name
            """[{"start": 100, "stop": 200, "policy": "NON_EXISTENT_POLICY_TYPE", "generatedAt": 300}]""",
            // policy is an integer
            """[{"start": 100, "stop": 200, "policy": 12345, "generatedAt": 300}]""",
            // generatedAt is an object
            """[{"start": 100, "stop": 200, "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": {"nested": 1}}]"""
        )

        for (snippet in badTypeObjects) {
            fakePrefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, snippet).apply()
            val pending = store.getPendingCandidates()
            assertTrue("JSON with invalid field types must safely return empty list", pending.isEmpty())
        }
    }

    @Test
    fun testStore_jsonWithMissingRequiredFields_returnsEmptyList() {
        val missingFieldSnippets = listOf(
            // Missing start
            """[{"stop": 200, "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": 300}]""",
            // Missing stop
            """[{"start": 100, "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": 300}]""",
            // Missing policy
            """[{"start": 100, "stop": 200, "generatedAt": 300}]""",
            // Missing generatedAt
            """[{"start": 100, "stop": 200, "policy": "OVERNIGHT_LONGEST_GAP"}]"""
        )

        for (snippet in missingFieldSnippets) {
            fakePrefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, snippet).apply()
            val pending = store.getPendingCandidates()
            assertTrue("JSON missing required fields must return empty list safely", pending.isEmpty())
        }
    }

    @Test
    fun testStore_jsonWithInvertedTimestamps_returnsEmptyListSafely() {
        val invertedTimestamps = listOf(
            // start == stop
            """[{"start": 1000, "stop": 1000, "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": 2000}]""",
            // start > stop
            """[{"start": 2000, "stop": 1000, "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": 2000}]"""
        )

        for (snippet in invertedTimestamps) {
            fakePrefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, snippet).apply()
            val pending = store.getPendingCandidates()
            assertTrue("Inverted timestamps violating stop > start must safely return empty list", pending.isEmpty())
        }
    }

    @Test
    fun testStore_missingDetectorVersion_defaultsToVersion1() {
        fakePrefs.edit().putString(
            AutoSleepConfig.PENDING_JSON_KEY,
            """[{"start": 1000, "stop": 2000, "policy": "OVERNIGHT_LONGEST_GAP", "generatedAt": 3000}]"""
        ).apply()

        val pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        assertEquals(1, pending[0].detectorVersion)
        assertEquals("1:OVERNIGHT_LONGEST_GAP:1000:2000", pending[0].fingerprint)
    }

    @Test
    fun testStore_corruptedStore_recoversOnNextValidWrite() {
        fakePrefs.edit()
            .putString(AutoSleepConfig.PENDING_JSON_KEY, "Corrupted Garbage Pending")
            .putString(AutoSleepConfig.REJECTED_JSON_KEY, "Corrupted Garbage Rejected")
            .apply()

        assertTrue(store.getPendingCandidates().isEmpty())
        assertFalse(store.isRejected("1:OVERNIGHT_LONGEST_GAP:100:200"))

        val candidate = SleepCandidate(
            start = 5_000_000L,
            stop = 10_000_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 10_000_000L
        )

        val added = store.addPending(candidate)
        assertTrue("Write after corruption must succeed and heal store", added)

        val pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        assertEquals(candidate.fingerprint, pending[0].fingerprint)

        // Test rejecting candidate heals rejected store
        store.markRejected(candidate, 12_000_000L)
        assertTrue(store.getPendingCandidates().isEmpty())
        assertTrue(store.isRejected(candidate.fingerprint, 12_000_000L))
    }

    // =========================================================================
    // SECTION 2: CANDIDATE STORE - CAPACITY OVERFLOW (>100 CANDIDATES)
    // =========================================================================

    @Test
    fun testStore_pendingOverflow_150Candidates_strictlyCappedAt7SortedByStopDesc() {
        val total = 150
        for (i in 1..total) {
            val start = i * 1_000_000L
            val stop = start + 500_000L
            val candidate = SleepCandidate(
                start = start,
                stop = stop,
                policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                generatedAt = stop
            )
            store.addPending(candidate)
        }

        val pending = store.getPendingCandidates()
        assertEquals("Pending store must strictly enforce MAX_PENDING = 7", AutoSleepConfig.MAX_PENDING, pending.size)

        // Verify sorted descending by stop
        for (i in 0 until pending.size - 1) {
            assertTrue(
                "Pending list must be strictly sorted by stop desc (${pending[i].stop} >= ${pending[i + 1].stop})",
                pending[i].stop >= pending[i + 1].stop
            )
        }

        // Verify that the 7 retained candidates are the latest ones (from i = 150 down to 144)
        for (idx in 0 until 7) {
            val expectedI = total - idx
            val expectedStop = expectedI * 1_000_000L + 500_000L
            assertEquals(expectedStop, pending[idx].stop)
        }

        // Verify that candidate i=1 is long evicted
        val oldFingerprint = "1:OVERNIGHT_LONGEST_GAP:1000000:1500000"
        assertFalse(store.isPending(oldFingerprint))
    }

    @Test
    fun testStore_rejectedOverflow_150Candidates_strictlyCappedAt30NewestRetained() {
        val baseTime = 1_000_000_000L
        val total = 150

        for (i in 1..total) {
            val candidate = SleepCandidate(
                start = i * 1000L,
                stop = i * 1000L + 500L,
                policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                generatedAt = i * 1000L
            )
            store.markRejected(candidate, baseTime + i * 1000L)
        }

        val queryTime = baseTime + total * 1000L

        // Latest 30 (from i = 121 to 150) must be present
        for (i in 121..150) {
            val fingerprint = "1:OVERNIGHT_LONGEST_GAP:${i * 1000L}:${i * 1000L + 500L}"
            assertTrue("Candidate i=$i (within latest 30) must be retained in rejected store", store.isRejected(fingerprint, queryTime))
        }

        // Oldest 120 (from i = 1 to 120) must be evicted
        for (i in 1..120) {
            val fingerprint = "1:OVERNIGHT_LONGEST_GAP:${i * 1000L}:${i * 1000L + 500L}"
            assertFalse("Candidate i=$i (older than latest 30) must be evicted", store.isRejected(fingerprint, queryTime))
        }
    }

    // =========================================================================
    // SECTION 3: CANDIDATE STORE - TTL EXPIRY NEAR 96h BOUNDARIES
    // =========================================================================

    @Test
    fun testStore_ttlExpiryNear96hBoundaries() {
        val candidate = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_028_800_000L
        )
        val rejectTime = 2_000_000_000L
        val ttlMs = 96L * 3600 * 1000L // 345,600,000 ms

        store.markRejected(candidate, rejectTime)

        // 1. 95h 59m (1 minute before expiry) -> RETAINED
        val t95h59m = rejectTime + ttlMs - 60_000L
        assertTrue("Candidate must be rejected at 95h 59m", store.isRejected(candidate.fingerprint, t95h59m))
        assertFalse("Cannot add pending candidate at 95h 59m", store.addPending(candidate, t95h59m))

        // 2. Exact 96h 00m 00s (boundary cutoff) -> RETAINED (rejectedAt >= cutoff where cutoff = now - ttl)
        val tExact96h = rejectTime + ttlMs
        assertTrue("Candidate must be rejected at exact 96h boundary", store.isRejected(candidate.fingerprint, tExact96h))
        assertFalse("Cannot add pending candidate at exact 96h", store.addPending(candidate, tExact96h))

        // 3. 96h 00m 00s + 1ms -> EXPIRED & PURGED
        val t96hPlus1ms = rejectTime + ttlMs + 1L
        assertFalse("Candidate must NOT be rejected at 96h + 1ms", store.isRejected(candidate.fingerprint, t96hPlus1ms))

        // 4. 96h 01m (1 minute after expiry) -> EXPIRED
        val t96h01m = rejectTime + ttlMs + 60_000L
        assertFalse("Candidate must NOT be rejected at 96h 01m", store.isRejected(candidate.fingerprint, t96h01m))

        // 5. Re-add succeeds after expiry
        val reAdded = store.addPending(candidate, t96h01m)
        assertTrue("Re-adding candidate after 96h expiry must succeed", reAdded)
        assertEquals(1, store.getPendingCandidates().size)
    }

    // =========================================================================
    // SECTION 4: CANDIDATE STORE - CONCURRENT INGESTION & REJECTION STRESS HARNESS
    // =========================================================================

    /**
     * Thread-safe SharedPreferences wrapper to test store behavior under concurrent access.
     */
    private class ThreadSafeFakePreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = (data[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pendingWrites = mutableMapOf<String, Any?>()
            private var clearAll = false

            @Synchronized override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) pendingWrites[key] = value; return this }
            @Synchronized override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor { if (key != null) pendingWrites[key] = values; return this }
            @Synchronized override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) pendingWrites[key] = value; return this }
            @Synchronized override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) pendingWrites[key] = value; return this }
            @Synchronized override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) pendingWrites[key] = value; return this }
            @Synchronized override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) pendingWrites[key] = value; return this }
            @Synchronized override fun remove(key: String?): SharedPreferences.Editor { if (key != null) pendingWrites[key] = null; return this }
            @Synchronized override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            @Synchronized override fun commit(): Boolean { apply(); return true }
            @Synchronized override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pendingWrites) {
                    if (v == null) data.remove(k) else data[k] = v
                }
                pendingWrites.clear()
                clearAll = false
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun testStore_rapidConcurrentOperations_threadSafety() {
        val concurrentPrefs = ThreadSafeFakePreferences()
        val concurrentStore = AutoSleepCandidateStore(concurrentPrefs)

        val threadCount = 16
        val operationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (threadId in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (op in 0 until operationsPerThread) {
                        val candidateId = threadId * 1000 + op
                        val start = candidateId * 10_000L
                        val stop = start + 5_000L
                        val candidate = SleepCandidate(
                            start = start,
                            stop = stop,
                            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                            generatedAt = stop
                        )

                        when (op % 4) {
                            0 -> concurrentStore.addPending(candidate)
                            1 -> concurrentStore.markRejected(candidate)
                            2 -> concurrentStore.markAccepted(candidate)
                            3 -> {
                                concurrentStore.getPendingCandidates()
                                concurrentStore.isRejected(candidate.fingerprint)
                                concurrentStore.isPending(candidate.fingerprint)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent worker threads must complete within timeout", finished)
        assertEquals("Zero unhandled exceptions or crashes under concurrent execution", 0, errorCount.get())

        // Invariants must hold
        val finalPending = concurrentStore.getPendingCandidates()
        assertTrue("Pending count must never exceed MAX_PENDING (7)", finalPending.size <= AutoSleepConfig.MAX_PENDING)
    }

    // =========================================================================
    // SECTION 5: OVERLAP CALCULATOR - COMPLEX OVERLAPPING & NESTED INTERVALS
    // =========================================================================

    @Test
    fun testOverlap_chainOfThreeOverlappingIntervals() {
        // Candidate: [0 .. 10,000] (duration: 10,000 ms)
        val candidate = SleepCandidate(0L, 10_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 10_000L)

        // 3 overlapping intervals in chain:
        // s1: [1,000 .. 4,000] (3,000 ms)
        // s2: [3,000 .. 7,000] (overlaps s1 from 3,000 to 4,000)
        // s3: [6,000 .. 9,000] (overlaps s2 from 6,000 to 7,000)
        // Merged union = [1,000 .. 9,000] = 8,000 ms (80%)
        val s1 = Sleep().apply { start = 1_000L; stop = 4_000L }
        val s2 = Sleep().apply { start = 3_000L; stop = 7_000L }
        val s3 = Sleep().apply { start = 6_000L; stop = 9_000L }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(s1, s2, s3))
        assertEquals(0.80, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(s1, s2, s3)))
    }

    @Test
    fun testOverlap_deeplyNestedIntervals_mergesToEnclosingDuration() {
        // Candidate: [0 .. 10,000]
        val candidate = SleepCandidate(0L, 10_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 10_000L)

        // Outer: [1,000 .. 8,000] (7,000 ms = 70%)
        // Nested 1: [2,000 .. 6,000]
        // Nested 2: [3,000 .. 5,000]
        // Nested 3: [1,000 .. 4,000]
        val outer = Sleep().apply { start = 1_000L; stop = 8_000L }
        val n1 = Sleep().apply { start = 2_000L; stop = 6_000L }
        val n2 = Sleep().apply { start = 3_000L; stop = 5_000L }
        val n3 = Sleep().apply { start = 1_000L; stop = 4_000L }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(outer, n1, n2, n3))
        assertEquals(0.70, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(outer, n1, n2, n3)))
    }

    @Test
    fun testOverlap_multipleDisjointClustersOfOverlappingIntervals() {
        // Candidate: [0 .. 10,000]
        val candidate = SleepCandidate(0L, 10_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 10_000L)

        // Cluster 1: [1,000..2,500] + [2,000..3,000] -> merged [1,000..3,000] (2,000 ms)
        val c1a = Sleep().apply { start = 1_000L; stop = 2_500L }
        val c1b = Sleep().apply { start = 2_000L; stop = 3_000L }

        // Cluster 2: [6,000..7,500] + [7,000..9,000] -> merged [6,000..9,000] (3,000 ms)
        val c2a = Sleep().apply { start = 6_000L; stop = 7_500L }
        val c2b = Sleep().apply { start = 7_000L; stop = 9_000L }

        // Total union = 2,000 + 3,000 = 5,000 ms (50%)
        val sleeps = listOf(c1a, c1b, c2a, c2b)
        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, sleeps)
        assertEquals(0.50, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, sleeps))
    }

    @Test
    fun testOverlap_unsortedInputList_mergesCorrectly() {
        val candidate = SleepCandidate(0L, 10_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 10_000L)

        val s1 = Sleep().apply { start = 7_000L; stop = 9_000L } // 2,000 ms
        val s2 = Sleep().apply { start = 1_000L; stop = 3_000L } // 2,000 ms
        val s3 = Sleep().apply { start = 4_000L; stop = 6_000L } // 2,000 ms

        // List provided in reverse chronological order
        val reverseList = listOf(s1, s3, s2)
        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, reverseList)
        assertEquals(0.60, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, reverseList))
    }

    @Test
    fun testOverlap_duplicateIntervals_mergesCorrectly() {
        val candidate = SleepCandidate(0L, 10_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 10_000L)

        val duplicates = (1..5).map {
            Sleep().apply { start = 2_000L; stop = 6_000L } // 4,000 ms (40%)
        }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, duplicates)
        assertEquals(0.40, coverage, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, duplicates))
    }

    // =========================================================================
    // SECTION 6: OVERLAP CALCULATOR - EXACT BOUNDARY THRESHOLDS
    // =========================================================================

    @Test
    fun testOverlap_exactThresholdBoundaries() {
        // Candidate of 100,000 ms
        val candidate = SleepCandidate(0L, 100_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 100_000L)

        // 1. Exact 49,999 ms = 49.999%
        val sleep49999 = Sleep().apply { start = 0L; stop = 49_999L }
        val cov49999 = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep49999))
        assertEquals(0.49999, cov49999, 0.000001)
        assertFalse("49.999% must NOT be suppressed", AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep49999)))

        // 2. Exact 50,000 ms = 50.000%
        val sleep50000 = Sleep().apply { start = 0L; stop = 50_000L }
        val cov50000 = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep50000))
        assertEquals(0.50000, cov50000, 0.000001)
        assertTrue("50.000% MUST be suppressed", AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep50000)))

        // 3. Exact 50,001 ms = 50.001%
        val sleep50001 = Sleep().apply { start = 0L; stop = 50_001L }
        val cov50001 = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep50001))
        assertEquals(0.50001, cov50001, 0.000001)
        assertTrue("50.001% MUST be suppressed", AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep50001)))
    }

    // =========================================================================
    // SECTION 7: OVERLAP CALCULATOR - CONTIGUOUS TOUCHING INTERVALS
    // =========================================================================

    @Test
    fun testOverlap_multipleContiguousTouchingIntervals_mergesContinuousSpan() {
        val candidate = SleepCandidate(0L, 30_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 30_000L)

        // Chain of 3 contiguous touching intervals: [0..10,000], [10,000..20,000], [20,000..30,000]
        val s1 = Sleep().apply { start = 0L; stop = 10_000L }
        val s2 = Sleep().apply { start = 10_000L; stop = 20_000L }
        val s3 = Sleep().apply { start = 20_000L; stop = 30_000L }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(s1, s2, s3))
        assertEquals(1.0, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(s1, s2, s3)))
    }

    @Test
    fun testOverlap_touchingBoundaries_zeroOverlap() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L)

        // Sleep ends exactly where candidate starts: [5,000 .. 10,000]
        val touchingLeft = Sleep().apply { start = 5_000L; stop = 10_000L }
        // Sleep starts exactly where candidate ends: [20,000 .. 25,000]
        val touchingRight = Sleep().apply { start = 20_000L; stop = 25_000L }

        val covLeft = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(touchingLeft))
        assertEquals(0.0, covLeft, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(touchingLeft)))

        val covRight = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(touchingRight))
        assertEquals(0.0, covRight, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(touchingRight)))

        val covBoth = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(touchingLeft, touchingRight))
        assertEquals(0.0, covBoth, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(touchingLeft, touchingRight)))
    }

    // =========================================================================
    // SECTION 8: OVERLAP CALCULATOR - 0-DURATION & DEGENERATE CASES
    // =========================================================================

    @Test
    fun testCandidate_zeroOrInvertedDuration_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            SleepCandidate(10_000L, 10_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 10_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SleepCandidate(20_000L, 10_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 10_000L)
        }
    }

    @Test
    fun testOverlap_zeroDurationSleep_contributesZeroCoverage() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L)

        // Sleep with start == stop inside candidate window
        val zeroSleep = Sleep().apply { start = 15_000L; stop = 15_000L }
        val covZero = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(zeroSleep))
        assertEquals(0.0, covZero, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(zeroSleep)))

        // Sleep with start > stop (inverted)
        val invertedSleep = Sleep().apply { start = 18_000L; stop = 12_000L }
        val covInverted = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(invertedSleep))
        assertEquals(0.0, covInverted, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(invertedSleep)))
    }

    @Test
    fun testOverlap_emptySleepList_returnsZeroNotSuppressed() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L)
        val cov = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, emptyList())
        assertEquals(0.0, cov, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, emptyList()))
    }

    @Test
    fun testOverlap_veryLargeTimestamps_noLongOverflow() {
        val largeStart = 1_800_000_000_000L
        val largeStop = largeStart + 28_800_000L // 8 hours later
        val candidate = SleepCandidate(largeStart, largeStop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, largeStop)

        // 4 hours overlap (50%)
        val sleep = Sleep().apply {
            start = largeStart
            stop = largeStart + 14_400_000L
        }

        val cov = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep))
        assertEquals(0.50, cov, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep)))
    }
}
