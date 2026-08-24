/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Empirical challenger stress test suite for AutoSleepBackend.
 *
 * Rigorously challenges:
 * 1. High-concurrency parallel scan invocations with Mutex serialization (0 duplicate Room inserts or store entries).
 * 2. Multi-instance backend concurrency sharing companion Mutex.
 * 3. Critical section exclusion invariants under artificial I/O delay.
 * 4. Scan state machine transitions (disabled <-> enabled, permission granted <-> revoked, unsupported <-> supported, empty <-> populated).
 * 5. Precondition gate evaluation precedence.
 * 6. Dynamic policy and save mode toggles.
 * 7. Exception safety, lock release guarantees, and rapid flapping resilience.
 */
class AutoSleepBackendEmpiricalStressTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore
    private lateinit var detector: AutoSleepDetector
    private lateinit var fakeDao: FakeSleepDao

    private val tz = TimeZone.getTimeZone("UTC")
    private var baseTime: Long = 0L
    private var t1: Long = 0L // 23:00 Day 1
    private var t2: Long = 0L // 07:00 Day 2 (8h overnight)
    private var now: Long = 0L // 12:00 Day 2

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
        t2 = baseTime + 31 * 3600 * 1000L
        now = baseTime + 36 * 3600 * 1000L
    }

    // =========================================================================
    // SECTION 1: CONCURRENCY & MUTEX SERIALIZATION STRESS HARNESS
    // =========================================================================

    @Test
    fun testConcurrency_massiveConcurrentScans_autoSaveMode_zeroDuplicates() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        val coroutineCount = 50
        val results = coroutineScope {
            (1..coroutineCount).map {
                async(Dispatchers.Default) {
                    backend.scan()
                }
            }.awaitAll()
        }

        // Verification of scan results
        val totalAutoSaved = results.sumOf { it.autoSaved }
        val totalDiscovered = results.map { it.discovered }
        val allSuccessful = results.all { it.status == ScanStatus.SUCCESS }

        assertTrue("All concurrent scans must return ScanStatus.SUCCESS", allSuccessful)
        assertEquals("Total autoSaved across 50 parallel scans must be EXACTLY 1", 1, totalAutoSaved)
        assertEquals("All scans must discover the candidate", coroutineCount, totalDiscovered.filter { it == 1 }.size)

        // Verification of persistence state
        assertEquals("Room database MUST contain EXACTLY 1 inserted sleep record (0 duplicates)", 1, fakeDao.count())
        val savedSleep = fakeDao.getAll()[0]
        assertEquals(t1, savedSleep.start)
        assertEquals(t2, savedSleep.stop)

        // Store verification: in AUTO_SAVE mode, candidate is marked accepted, not pending
        assertTrue("Store pending candidates must be 0 in AUTO_SAVE mode", store.getPendingCandidates().isEmpty())
    }

    @Test
    fun testConcurrency_massiveConcurrentScans_suggestMode_zeroDuplicates() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        val coroutineCount = 50
        val results = coroutineScope {
            (1..coroutineCount).map {
                async(Dispatchers.Default) {
                    backend.scan()
                }
            }.awaitAll()
        }

        val totalQueued = results.sumOf { it.queued }
        val totalAutoSaved = results.sumOf { it.autoSaved }

        assertEquals("Total queued across 50 parallel scans must be EXACTLY 1", 1, totalQueued)
        assertEquals("Total autoSaved in SUGGEST mode must be 0", 0, totalAutoSaved)
        assertEquals("Pending store must contain EXACTLY 1 candidate", 1, store.getPendingCandidates().size)
        assertEquals("Room database must contain 0 records", 0, fakeDao.count())
    }

    @Test
    fun testConcurrency_multiInstance_sharedMutex_zeroDuplicates() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val instanceCount = 30
        val results = coroutineScope {
            (1..instanceCount).map {
                async(Dispatchers.Default) {
                    // Create distinct AutoSleepBackend instance per coroutine sharing the same DAO and store
                    val backendInstance = AutoSleepBackend(
                        preferences = prefs,
                        eventSource = source,
                        store = store,
                        detector = detector,
                        sleepDao = fakeDao,
                        timeZoneProvider = { tz },
                        nowProvider = { now }
                    )
                    backendInstance.scan()
                }
            }.awaitAll()
        }

        val totalAutoSaved = results.sumOf { it.autoSaved }
        assertEquals("Companion Mutex must synchronize across separate backend instances", 1, totalAutoSaved)
        assertEquals("Room database must contain exactly 1 sleep record", 1, fakeDao.count())
    }

    @Test
    fun testConcurrency_multiCandidateBatch_massiveParallelScans() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        // 3 consecutive days of overnight sleep in the 72h window
        val day1Start = baseTime + 23 * 3600 * 1000L
        val day1Stop = baseTime + 31 * 3600 * 1000L

        val day2Start = baseTime + 47 * 3600 * 1000L
        val day2Stop = baseTime + 55 * 3600 * 1000L

        val day3Start = baseTime + 71 * 3600 * 1000L
        val day3Stop = baseTime + 79 * 3600 * 1000L

        val testNow = baseTime + 85 * 3600 * 1000L

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) =
                listOf(day1Start, day1Stop, day2Start, day2Stop, day3Start, day3Stop)
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { testNow }
        )

        val coroutineCount = 30
        val results = coroutineScope {
            (1..coroutineCount).map {
                async(Dispatchers.Default) {
                    backend.scan()
                }
            }.awaitAll()
        }

        val totalAutoSaved = results.sumOf { it.autoSaved }
        assertEquals("Exactly 3 distinct candidates auto-saved across 30 concurrent scans", 3, totalAutoSaved)
        assertEquals("Room database must contain exactly 3 sleep records", 3, fakeDao.count())

        val savedStarts = fakeDao.getAll().map { it.start }.toSet()
        assertEquals(setOf(day1Start, day2Start, day3Start), savedStarts)
    }

    @Test
    fun testConcurrency_slowDaoAndEventSource_provesMutexExclusion() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val activeInCriticalSection = AtomicInteger(0)
        val maxConcurrentObserved = AtomicInteger(0)

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                val current = activeInCriticalSection.incrementAndGet()
                maxConcurrentObserved.updateAndGet { max -> kotlin.math.max(max, current) }
                delay(20) // Artificial delay inside critical section
                activeInCriticalSection.decrementAndGet()
                return listOf(t1, t2)
            }
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        val coroutineCount = 20
        coroutineScope {
            (1..coroutineCount).map {
                async(Dispatchers.Default) {
                    backend.scan()
                }
            }.awaitAll()
        }

        assertEquals("At no time may more than 1 coroutine enter the critical section", 1, maxConcurrentObserved.get())
        assertEquals(1, fakeDao.count())
    }

    @Test
    fun testConcurrency_exceptionInEventSource_releasesMutexCleanly() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        var shouldThrow = true
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                if (shouldThrow) {
                    throw IllegalStateException("Simulated hardware error")
                }
                return listOf(t1, t2)
            }
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        // 1. Scan throws exception
        var threw = false
        try {
            backend.scan()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("Scan must throw IllegalStateException when source throws", threw)

        // 2. Next scan must NOT deadlock and must acquire Mutex cleanly
        shouldThrow = false
        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.autoSaved)
        assertEquals(1, fakeDao.count())
    }

    // =========================================================================
    // SECTION 2: SCAN STATE TRANSITIONS & STATE MACHINE DYNAMICS
    // =========================================================================

    @Test
    fun testStateTransition_disabledToEnabled_andBack() = runBlocking {
        var sourceQueried = false
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                sourceQueried = true
                return listOf(t1, t2)
            }
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        // Phase 1: Disabled
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        val r1 = backend.scan()
        assertEquals(ScanStatus.DISABLED, r1.status)
        assertFalse("Source must not be queried when disabled", sourceQueried)

        // Phase 2: Enabled
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val r2 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, r2.status)
        assertTrue("Source must be queried when enabled", sourceQueried)
        assertEquals(1, r2.discovered)

        // Phase 3: Disabled again
        sourceQueried = false
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        val r3 = backend.scan()
        assertEquals(ScanStatus.DISABLED, r3.status)
        assertFalse("Source must not be queried after disabling", sourceQueried)
    }

    @Test
    fun testStateTransition_permissionGrantedToRevoked_andBack() = runBlocking {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()

        var hasPermission = false
        var sourceQueried = false
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                sourceQueried = true
                return listOf(t1, t2)
            }
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            hasPermissionProvider = { hasPermission },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        // Phase 1: No permission
        hasPermission = false
        val r1 = backend.scan()
        assertEquals(ScanStatus.NO_PERMISSION, r1.status)
        assertFalse(sourceQueried)

        // Phase 2: Permission granted
        hasPermission = true
        val r2 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, r2.status)
        assertTrue(sourceQueried)

        // Phase 3: Permission revoked mid-operation
        hasPermission = false
        sourceQueried = false
        val r3 = backend.scan()
        assertEquals(ScanStatus.NO_PERMISSION, r3.status)
        assertFalse(sourceQueried)
    }

    @Test
    fun testStateTransition_unsupportedPlatformToSupported() = runBlocking {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()

        var isSupported = false
        var sourceQueried = false
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                sourceQueried = true
                return listOf(t1, t2)
            }
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { isSupported },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        // Phase 1: Unsupported (API < 28)
        val r1 = backend.scan()
        assertEquals(ScanStatus.UNSUPPORTED, r1.status)
        assertFalse(sourceQueried)

        // Phase 2: Supported (API >= 28)
        isSupported = true
        val r2 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, r2.status)
        assertTrue(sourceQueried)
    }

    @Test
    fun testStateTransition_precedenceOfPreconditionGates() = runBlocking {
        var sourceQueried = false
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                sourceQueried = true
                return listOf(t1, t2)
            }
        }

        // Gate 1 (Enabled) takes precedence over Gate 2 (Supported) and Gate 3 (Permission)
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        val backend1 = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { false },
            hasPermissionProvider = { false }
        )
        assertEquals(ScanStatus.DISABLED, backend1.scan().status)
        assertFalse(sourceQueried)

        // Gate 2 (Supported) takes precedence over Gate 3 (Permission)
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val backend2 = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            isSupportedProvider = { false },
            hasPermissionProvider = { false }
        )
        assertEquals(ScanStatus.UNSUPPORTED, backend2.scan().status)
        assertFalse(sourceQueried)
    }

    @Test
    fun testStateTransition_emptyEventSourceToPopulated_andBack() = runBlocking {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()

        var currentEvents = listOf<Long>()
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = currentEvents
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        // Phase 1: Empty event source -> ScanStatus.EMPTY
        currentEvents = emptyList()
        val r1 = backend.scan()
        assertEquals(ScanStatus.EMPTY, r1.status)
        assertEquals(0, r1.discovered)
        assertEquals(0, store.getPendingCandidates().size)

        // Phase 2: Single event -> no gaps -> ScanStatus.EMPTY
        currentEvents = listOf(t1)
        val r2 = backend.scan()
        assertEquals(ScanStatus.EMPTY, r2.status)

        // Phase 3: Populated events with valid overnight gap -> ScanStatus.SUCCESS
        currentEvents = listOf(t1, t2)
        val r3 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, r3.status)
        assertEquals(1, r3.discovered)
        assertEquals(1, r3.queued)
        assertEquals(1, store.getPendingCandidates().size)

        // Phase 4: Event source cleared again -> ScanStatus.EMPTY
        currentEvents = emptyList()
        val r4 = backend.scan()
        assertEquals(ScanStatus.EMPTY, r4.status)
        // Store still preserves previously queued candidate
        assertEquals(1, store.getPendingCandidates().size)
    }

    @Test
    fun testStateTransition_policyToggle_overnightVsPureLongestGap() = runBlocking {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()

        // 2 gaps:
        // Gap 1 (Daytime): 08:00 to 18:00 (10h duration, not overnight)
        // Gap 2 (Overnight): 23:00 to 07:00 next day (8h duration, intersects 00:00-06:00)
        val dayStart = baseTime + 8 * 3600 * 1000L
        val dayStop = baseTime + 18 * 3600 * 1000L
        val nightStart = baseTime + 23 * 3600 * 1000L
        val nightStop = baseTime + 31 * 3600 * 1000L

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) =
                listOf(dayStart, dayStop, nightStart, nightStop)
        }

        // Test with OVERNIGHT_LONGEST_GAP policy
        prefs.edit().putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name).apply()
        val backendOvernight = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )
        val rOvernight = backendOvernight.scan()
        assertEquals(ScanStatus.SUCCESS, rOvernight.status)
        assertEquals(1, rOvernight.discovered)
        val queuedOvernight = store.getPendingCandidates()
        assertEquals(1, queuedOvernight.size)
        assertEquals(nightStart, queuedOvernight[0].start)
        assertEquals(nightStop, queuedOvernight[0].stop)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, queuedOvernight[0].policy)

        store.clear()

        // Test with PURE_LONGEST_GAP policy -> Selects the 10h daytime gap
        prefs.edit().putString(AutoSleepConfig.POLICY_KEY, AutoSleepPolicyId.PURE_LONGEST_GAP.name).apply()
        val backendPure = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )
        val rPure = backendPure.scan()
        assertEquals(ScanStatus.SUCCESS, rPure.status)
        assertEquals(1, rPure.discovered)
        val queuedPure = store.getPendingCandidates()
        assertEquals(1, queuedPure.size)
        assertEquals(dayStart, queuedPure[0].start)
        assertEquals(dayStop, queuedPure[0].stop)
        assertEquals(AutoSleepPolicyId.PURE_LONGEST_GAP, queuedPure[0].policy)
    }

    @Test
    fun testStateTransition_corruptedPolicyString_fallsBackToOvernightLongestGap() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.POLICY_KEY, "TOTALLY_CORRUPTED_POLICY_ENUM_STRING")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        val queued = store.getPendingCandidates()
        assertEquals(1, queued.size)
        assertEquals(AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, queued[0].policy)
    }

    @Test
    fun testStateTransition_rapidFlappingStress() = runBlocking {
        val iterations = 50
        var permissionAllowed = true

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            hasPermissionProvider = { permissionAllowed },
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        for (i in 1..iterations) {
            val enabled = (i % 2 == 0)
            permissionAllowed = (i % 3 != 0)
            prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, enabled).apply()

            val result = backend.scan()

            if (!enabled) {
                assertEquals("Iteration $i: expected DISABLED", ScanStatus.DISABLED, result.status)
            } else if (!permissionAllowed) {
                assertEquals("Iteration $i: expected NO_PERMISSION", ScanStatus.NO_PERMISSION, result.status)
            } else {
                assertEquals("Iteration $i: expected SUCCESS", ScanStatus.SUCCESS, result.status)
            }
        }
    }

    // =========================================================================
    // SECTION 3: ON_AUTO_SAVED PERSISTENCE CALLBACK & OVERLAP EDGES
    // =========================================================================

    @Test
    fun testAutoSave_onAutoSavedCallbackInvoked() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        var callbackInvoked = false
        var savedSleepStart = 0L

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now },
            onAutoSaved = { sleep ->
                callbackInvoked = true
                savedSleepStart = sleep.start
            }
        )

        val result = backend.scan()
        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.autoSaved)
        assertTrue("onAutoSaved hook MUST be invoked during AUTO_SAVE", callbackInvoked)
        assertEquals(t1, savedSleepStart)
    }

    @Test
    fun testAutoSave_callbackExceptionDoesNotCorruptSubsequentScans() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
            .apply()

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }

        var shouldThrow = true
        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now },
            onAutoSaved = {
                if (shouldThrow) {
                    throw RuntimeException("Backup failed")
                }
            }
        )

        var threw = false
        try {
            backend.scan()
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("Scan must throw RuntimeException when callback throws", threw)

        // Verify next scan succeeds after throwing
        shouldThrow = false
        val r2 = backend.scan()
        assertEquals(ScanStatus.SUCCESS, r2.status)
    }

    @Test
    fun testScan_lookbackWindow72hExactBoundary() = runBlocking {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()

        var capturedBegin = 0L
        var capturedEnd = 0L

        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> {
                capturedBegin = beginInclusive
                capturedEnd = endExclusive
                return listOf(t1, t2)
            }
        }

        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = source,
            store = store,
            detector = detector,
            sleepDao = fakeDao,
            timeZoneProvider = { tz },
            nowProvider = { now }
        )

        backend.scan()

        val expectedLookbackMs = 72L * 3600 * 1000L
        assertEquals(now, capturedEnd)
        assertEquals(now - expectedLookbackMs, capturedBegin)
    }
}
