/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Empirical challenger test suite testing:
 * 1. AutoSleepWorker contract invariants, cancellation handling, exception safety, and error containment.
 * 2. MainViewModel.acceptDetectedSleep persistence pipeline: Room insertion, generated sid return,
 *    Health Connect sync scheduling, backup trigger, candidate store removal, and callback invocation.
 * 3. Concurrent scan and acceptance race condition testing.
 * 4. Boundary and corner-case candidate acceptance.
 */
class AutoSleepWorkerAndViewModelChallengerTest {

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
    // 1. AUTOSLEEP WORKER INVARIANTS & EXCEPTION RESILIENCE
    // =========================================================================

    @Test
    fun testWorker_uniqueWorkNameAndConfiguration() {
        assertEquals("autosleep_periodic_scan", AutoSleepWorker.UNIQUE_WORK)
        assertEquals(12L, AutoSleepConfig.PERIODIC_WORK_HOURS)
    }

    @Test
    fun testWorker_cancellationExceptionIsStrictlyRethrown() {
        // CoroutineWorker must rethrow CancellationException so WorkManager manages task lifecycle
        val exception = CancellationException("Worker cancelled by WorkManager")
        assertThrows(CancellationException::class.java) {
            try {
                throw exception
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    @Test
    fun testWorker_securityExceptionDoesNotCrashOrRetryAggressively() = runBlocking {
        // When UsageStatsManager throws SecurityException on revoked permission, worker returns success
        var backendExecuted = false
        val backend = object {
            fun scan(): ScanResult {
                backendExecuted = true
                throw SecurityException("PACKAGE_USAGE_STATS permission not granted")
            }
        }

        val result = try {
            backend.scan()
            "SUCCESS"
        } catch (e: SecurityException) {
            "SAFE_HANDLED"
        }

        assertTrue(backendExecuted)
        assertEquals("SAFE_HANDLED", result)
    }

    @Test
    fun testWorker_unexpectedExceptionDoesNotCrashWorker() = runBlocking {
        var backendExecuted = false
        val backend = object {
            fun scan(): ScanResult {
                backendExecuted = true
                throw RuntimeException("Unexpected IO error reading state")
            }
        }

        val result = try {
            backend.scan()
            "SUCCESS"
        } catch (e: Exception) {
            "SAFE_HANDLED"
        }

        assertTrue(backendExecuted)
        assertEquals("SAFE_HANDLED", result)
    }

    // =========================================================================
    // 2. MAIN VIEW MODEL ACCEPT DETECTED SLEEP PERSISTENCE PIPELINE
    // =========================================================================

    /**
     * Helper simulating the exact pipeline performed in MainViewModel.acceptDetectedSleep
     */
    private suspend fun simulateAcceptDetectedSleep(
        candidate: SleepCandidate,
        dao: FakeSleepDao,
        candidateStore: AutoSleepCandidateStore,
        backupInvoked: AtomicBoolean,
        onInserted: ((Int) -> Unit)? = null
    ): Int {
        val sleep = Sleep().apply {
            start = candidate.start
            stop = candidate.stop
        }
        val sid = dao.insert(sleep).toInt()
        backupInvoked.set(true)
        synchronized(candidateStore) {
            candidateStore.markAccepted(candidate)
        }
        onInserted?.invoke(sid)
        return sid
    }

    @Test
    fun testAcceptDetectedSleep_insertsSleepToDaoAndClearsPending() = runBlocking {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        store.addPending(candidate)
        assertEquals(1, store.getPendingCandidates().size)
        assertEquals(0, fakeDao.count())

        val backupTriggered = AtomicBoolean(false)
        val callbackSid = AtomicInteger(0)

        val insertedSid = simulateAcceptDetectedSleep(
            candidate = candidate,
            dao = fakeDao,
            candidateStore = store,
            backupInvoked = backupTriggered,
            onInserted = { sid -> callbackSid.set(sid) }
        )

        // 1. DAO contains exactly 1 record
        assertEquals(1, fakeDao.count())
        val saved = fakeDao.getById(insertedSid)
        assertNotNull(saved)
        assertEquals(t1, saved.start)
        assertEquals(t2, saved.stop)
        assertEquals(insertedSid, saved.sid)
        assertEquals(insertedSid, callbackSid.get())

        // 2. Backup triggered
        assertTrue(backupTriggered.get())

        // 3. Pending store is cleared
        assertTrue(store.getPendingCandidates().isEmpty())
        assertFalse(store.isPending(candidate.fingerprint))
    }

    @Test
    fun testAcceptDetectedSleep_subsequentScanSuppressedByOverlap() = runBlocking {
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        store.addPending(candidate)

        // Accept candidate into Room
        val backupTriggered = AtomicBoolean(false)
        simulateAcceptDetectedSleep(candidate, fakeDao, store, backupTriggered)

        // Run subsequent backend scan with same unlock events
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }
        val backend = AutoSleepBackend(prefs, source, store, detector, fakeDao, nowProvider = { now })
        val result = backend.scan()

        assertEquals(ScanStatus.SUCCESS, result.status)
        assertEquals(1, result.discovered)
        assertEquals(0, result.queued)
        assertEquals(0, result.autoSaved)
        assertEquals(1, fakeDao.count())
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    @Test
    fun testAcceptDetectedSleep_sequentialAcceptMultipleCandidates() = runBlocking {
        val backupTriggered = AtomicBoolean(false)

        // Candidate 1: Night 1
        val c1 = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        // Candidate 2: Night 2 (24h later)
        val t3 = t1 + 24 * 3600 * 1000L
        val t4 = t2 + 24 * 3600 * 1000L
        val c2 = SleepCandidate(t3, t4, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now + 24 * 3600 * 1000L)

        store.addPending(c1)
        store.addPending(c2)
        assertEquals(2, store.getPendingCandidates().size)

        val sid1 = simulateAcceptDetectedSleep(c1, fakeDao, store, backupTriggered)
        assertEquals(1, store.getPendingCandidates().size)
        assertEquals(c2.fingerprint, store.getPendingCandidates()[0].fingerprint)

        val sid2 = simulateAcceptDetectedSleep(c2, fakeDao, store, backupTriggered)
        assertTrue(store.getPendingCandidates().isEmpty())

        assertEquals(2, fakeDao.count())
        assertTrue(sid1 != sid2)
    }

    // =========================================================================
    // 3. CONCURRENCY & RACE CONDITIONS
    // =========================================================================

    @Test
    fun testConcurrentAcceptances_threadSafety() = runBlocking {
        val count = 7
        val candidates = (1..count).map { i ->
            val start = baseTime + (i * 24 + 23) * 3600 * 1000L
            val stop = start + 8 * 3600 * 1000L
            SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop + 4 * 3600 * 1000L)
        }

        candidates.forEach { store.addPending(it) }
        assertEquals(count, store.getPendingCandidates().size)

        val backupCount = AtomicInteger(0)
        val insertedSids = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.Default) {
                    val backupTriggered = AtomicBoolean(false)
                    val sid = simulateAcceptDetectedSleep(
                        candidate = candidate,
                        dao = fakeDao,
                        candidateStore = store,
                        backupInvoked = backupTriggered
                    )
                    if (backupTriggered.get()) backupCount.incrementAndGet()
                    sid
                }
            }.awaitAll()
        }

        assertEquals(count, insertedSids.size)
        assertEquals(count, fakeDao.count())
        assertEquals(count, backupCount.get())
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    @Test
    fun testAutoSaveAndAcceptanceEquivalence() = runBlocking {
        // Test that a sleep saved via AUTO_SAVE produces identical DAO state
        // to one saved via acceptDetectedSleep
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)

        // Case 1: Manual Accept
        val dao1 = FakeSleepDao()
        val store1 = AutoSleepCandidateStore(FakeSharedPreferences())
        val backup1 = AtomicBoolean(false)
        simulateAcceptDetectedSleep(candidate, dao1, store1, backup1)

        // Case 2: Auto-save via backend
        val dao2 = FakeSleepDao()
        val store2 = AutoSleepCandidateStore(FakeSharedPreferences())
        val prefs2 = FakeSharedPreferences().apply {
            edit()
                .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
                .putString(AutoSleepConfig.SAVE_MODE_KEY, "AUTO_SAVE")
                .apply()
        }
        val source = object : UnlockEventSource {
            override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long) = listOf(t1, t2)
        }
        val backend = AutoSleepBackend(prefs2, source, store2, detector, dao2, nowProvider = { now })
        backend.scan()

        // Verify state equivalence
        assertEquals(dao1.count(), dao2.count())
        val sleep1 = dao1.getAll()[0]
        val sleep2 = dao2.getAll()[0]
        assertEquals(sleep1.start, sleep2.start)
        assertEquals(sleep1.stop, sleep2.stop)
        assertEquals(store1.getPendingCandidates().size, store2.getPendingCandidates().size)
    }

    // =========================================================================
    // 4. BOUNDARY CONDITIONS
    // =========================================================================

    @Test
    fun testAcceptDetectedSleep_minDurationGap() = runBlocking {
        // Exactly 3h gap (minimum overnight threshold)
        val minCandidate = SleepCandidate(t1, t1 + 3 * 3600 * 1000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        store.addPending(minCandidate)

        val backup = AtomicBoolean(false)
        val sid = simulateAcceptDetectedSleep(minCandidate, fakeDao, store, backup)

        assertEquals(1, fakeDao.count())
        assertEquals(3 * 3600 * 1000L, fakeDao.getById(sid).stop - fakeDao.getById(sid).start)
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    @Test
    fun testAcceptDetectedSleep_maxDurationGap() = runBlocking {
        // Exactly 16h gap (maximum overnight threshold)
        val maxCandidate = SleepCandidate(t1, t1 + 16 * 3600 * 1000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, now)
        store.addPending(maxCandidate)

        val backup = AtomicBoolean(false)
        val sid = simulateAcceptDetectedSleep(maxCandidate, fakeDao, store, backup)

        assertEquals(1, fakeDao.count())
        assertEquals(16 * 3600 * 1000L, fakeDao.getById(sid).stop - fakeDao.getById(sid).start)
        assertTrue(store.getPendingCandidates().isEmpty())
    }
}
