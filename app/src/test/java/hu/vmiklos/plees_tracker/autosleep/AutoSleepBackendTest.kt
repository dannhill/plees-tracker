/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integrated unit test suite for AutoSleepBackend orchestrator.
 * Covers full test cases UT-BE-001 through UT-BE-010.
 */
class AutoSleepBackendTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore
    private lateinit var detector: AutoSleepDetector
    private lateinit var fakeDao: FakeSleepDao

    private val tz = TimeZone.getTimeZone("UTC")
    private var baseTime: Long = 0L
    private var t1: Long = 0L // 23:00
    private var t2: Long = 0L // 07:00 next day (8h duration)
    private var now: Long = 0L // 12:00 next day

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(prefs)
        detector = AutoSleepDetector()
        fakeDao = FakeSleepDao()

        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        baseTime = cal.timeInMillis
        t1 = baseTime + 23 * 3600 * 1000L
        t2 = baseTime + 31 * 3600 * 1000L // 8h overnight
        now = baseTime + 36 * 3600 * 1000L
    }

    /**
     * UT-BE-001: Feature disabled skips event source
     */
    @Test
    fun testUT_BE_001_featureDisabledSkipsSource() = runBlocking {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        var sourceQueried = false
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                sourceQueried = true
                return emptyList()
            }
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao)
        val result = backend.scan()

        assertEquals(ScanStatus.DISABLED, result.status)
        assertFalse("Event source must NOT be queried when feature is disabled", sourceQueried)
    }

    /**
     * UT-BE-002: No permission skips event source
     */
    @Test
    fun testUT_BE_002_noPermissionSkipsSource() = runBlocking {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        var sourceQueried = false
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                sourceQueried = true
                return emptyList()
            }
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            hasPermissionProvider = { false } // Permission denied
        )
        val result = backend.scan()

        assertEquals(ScanStatus.NO_PERMISSION, result.status)
        assertFalse("Event source must NOT be queried when permission is absent", sourceQueried)
    }

    /**
     * UT-BE-003: New candidate in SUGGEST mode
     */
    @Test
    fun testUT_BE_003_newCandidateSuggestMode() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(1, result.queued)
        assertEquals(0, result.autoSaved)
        assertEquals(1, store.getPendingCandidates().size)
        assertEquals(0, fakeDao.count())
    }

    /**
     * UT-BE-004: Deduplication in pending candidate store during scan
     */
    @Test
    fun testUT_BE_004_existingPendingDeduplicatedInScan() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        // Pre-populate store with candidate
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        store.addPending(candidate)
        assertEquals(1, store.getPendingCandidates().size)

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.queued) // Skipped duplicate
        assertEquals(0, result.autoSaved)
        assertEquals("Store must still contain exactly 1 item", 1, store.getPendingCandidates().size)
    }

    /**
     * UT-BE-005: Suppression of candidate in rejected store during scan
     */
    @Test
    fun testUT_BE_005_rejectedCandidateSuppressedInScan() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        // Mark candidate rejected 12 hours ago (well within 96h TTL)
        store.markRejected(candidate, now - 12 * 3600 * 1000L)

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.queued) // Suppressed by rejection
        assertEquals(0, result.autoSaved)
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    /**
     * UT-BE-006: Overlap coverage 49.0% -> NOT suppressed during scan
     */
    @Test
    fun testUT_BE_006_scanOverlap49PercentNotSuppressed() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        // Room has manual sleep covering 49.0% (3.92h) of 8h (28,800,000 ms)
        val manualSleep = Sleep().apply {
            start = t1
            stop = t1 + (28_800_000L * 0.49).toLong()
        }
        fakeDao.insert(manualSleep)

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(1, result.queued) // Not suppressed!
        assertEquals(1, store.getPendingCandidates().size)
    }

    /**
     * UT-BE-007: Overlap coverage 50.0% -> SUPPRESSED during scan
     */
    @Test
    fun testUT_BE_007_scanOverlap50PercentSuppressed() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        // Room has manual sleep covering exactly 50.0% (4.0h = 14,400,000 ms)
        val manualSleep = Sleep().apply {
            start = t1
            stop = t1 + 14_400_000L
        }
        fakeDao.insert(manualSleep)

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.queued) // Suppressed!
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    /**
     * UT-BE-008: Multiple fragmented Room sleeps merging to >50% -> SUPPRESSED during scan
     */
    @Test
    fun testUT_BE_008_scanMultipleSleepsMergeSuppressed() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        // Sleep 1: 01:00 to 03:30 relative to t1 (2.5h)
        val s1 = Sleep().apply {
            start = t1 + 3_600_000L
            stop = t1 + 12_600_000L
        }
        // Sleep 2: 03:00 to 06:00 relative to t1 (3.0h) -> Merged: 01:00 to 06:00 (5.0h = 62.5%)
        val s2 = Sleep().apply {
            start = t1 + 10_800_000L
            stop = t1 + 21_600_000L
        }
        fakeDao.insert(listOf(s1, s2))

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.queued) // Suppressed because 62.5% >= 50%!
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    /**
     * UT-BE-009: New candidate in AUTO_SAVE mode inserts to Room
     */
    @Test
    fun testUT_BE_009_newCandidateAutoSaveMode() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.queued)
        assertEquals(1, result.autoSaved)
        assertEquals(1, fakeDao.count())

        val inserted = fakeDao.getAll()[0]
        assertEquals(t1, inserted.start)
        assertEquals(t2, inserted.stop)
    }

    /**
     * UT-BE-010: Concurrent scans serialized by Mutex without duplicate inserts
     */
    @Test
    fun testUT_BE_010_concurrentScansMutexSerialized() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })

        // Launch two parallel scans concurrently
        coroutineScope {
            val deferred1 = async { backend.scan() }
            val deferred2 = async { backend.scan() }
            val results = awaitAll(deferred1, deferred2)

            val totalAutoSaved = results.sumOf { it.autoSaved }
            assertEquals("Mutex serialization must ensure exactly 1 auto-save across concurrent calls", 1, totalAutoSaved)
            assertEquals("Room database must contain exactly 1 inserted sleep record", 1, fakeDao.count())
        }
    }
}
