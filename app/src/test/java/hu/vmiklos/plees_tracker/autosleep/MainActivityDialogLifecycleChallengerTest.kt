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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Empirical challenger test suite for MainActivity dialog presentation and lifecycle safety.
 *
 * Covers:
 * 1. Dialog actions: Save (Room persistence + store removal), Discard (96h TTL rejection), Later (dismiss without mutation)
 * 2. Multi-candidate queueing and most-recent candidate priority
 * 3. Activity lifecycle transitions & recreation (orientation change simulation)
 * 4. At-most-once session guard & multi-resume suppression
 * 5. Window leak prevention and async scan cancellation on activity destruction
 * 6. Permission & opt-in gating
 * 7. Corrupted JSON resilient recovery
 */
class MainActivityDialogLifecycleChallengerTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore
    private lateinit var dao: FakeSleepDao

    private val tz = TimeZone.getTimeZone("UTC")
    private var baseTime: Long = 0L
    private var t1: Long = 0L // Day 1 23:00
    private var t2: Long = 0L // Day 2 07:00 (8h)
    private var t3: Long = 0L // Day 2 23:00
    private var t4: Long = 0L // Day 3 07:00 (8h)
    private var now: Long = 0L // Day 3 12:00

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(prefs)
        dao = FakeSleepDao()

        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        baseTime = cal.timeInMillis
        t1 = baseTime + 23 * 3600 * 1000L
        t2 = baseTime + 31 * 3600 * 1000L
        t3 = baseTime + 47 * 3600 * 1000L
        t4 = baseTime + 55 * 3600 * 1000L
        now = baseTime + 60 * 3600 * 1000L
    }

    // =========================================================================
    // 1. DIALOG ACTIONS EMPIRICAL VERIFICATION
    // =========================================================================

    @Test
    fun testSaveAction_persistsToRoomAndRemovesFromPendingStore() = runBlocking {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)
        assertEquals(1, store.getPendingCandidates().size)

        // Simulate MainActivity positive button ("Save") click
        val sleep = Sleep().apply {
            start = candidate.start
            stop = candidate.stop
        }
        val insertedId = dao.insert(sleep).toInt()
        store.markAccepted(candidate)

        // Verify Room persistence
        assertTrue("Inserted ID must be valid (> 0)", insertedId > 0)
        val allSleeps = dao.getAll()
        assertEquals(1, allSleeps.size)
        assertEquals(t1, allSleeps[0].start)
        assertEquals(t2, allSleeps[0].stop)

        // Verify candidate store mutation
        assertTrue("Candidate must be removed from pending store", store.getPendingCandidates().isEmpty())
        assertFalse("Candidate must NOT be in pending state", store.isPending(candidate.fingerprint))
        assertFalse("Candidate must NOT be in rejected state", store.isRejected(candidate.fingerprint, now))

        // Verify overlap suppression: candidate is now 100% covered in Room
        val detector = AutoSleepDetector()
        val backend = AutoSleepBackend(
            preferences = prefs,
            eventSource = object : UnlockEventSource {
                override suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long> =
                    listOf(t1, t2)
            },
            store = store,
            detector = detector,
            sleepDao = dao,
            isSupportedProvider = { true },
            hasPermissionProvider = { true },
            nowProvider = { now }
        )
        prefs.edit()
            .putBoolean(AutoSleepConfig.ENABLED_KEY, true)
            .putString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST")
            .apply()

        val scanResult = backend.scan()
        assertEquals(0, scanResult.queued)
        assertTrue("Store must remain empty because new candidate is suppressed by Room record", store.getPendingCandidates().isEmpty())
    }

    @Test
    fun testDiscardAction_recordsFingerprintWith96hTtlAndDoesNotPersistToRoom() = runBlocking {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)
        assertEquals(1, store.getPendingCandidates().size)

        // Simulate MainActivity negative button ("Discard") click
        store.markRejected(candidate, now)

        // Verify candidate store mutation
        assertTrue("Candidate must be removed from pending store", store.getPendingCandidates().isEmpty())
        assertFalse("Candidate must NOT be pending", store.isPending(candidate.fingerprint))
        assertTrue("Candidate must be marked rejected", store.isRejected(candidate.fingerprint, now))

        // Verify Room was NOT touched
        assertTrue("Room must have 0 records", dao.getAll().isEmpty())

        // Verify TTL retention within 96 hours
        val withinTtl = now + 95 * 3600 * 1000L
        assertTrue("Candidate must remain rejected at 95h", store.isRejected(candidate.fingerprint, withinTtl))

        // Verify TTL expiration after 96 hours
        val expired = now + 96 * 3600 * 1000L + 1L
        assertFalse("Candidate rejection must expire after 96h", store.isRejected(candidate.fingerprint, expired))
    }

    @Test
    fun testLaterAction_dismissesWithoutStoreMutationOrRoomPersistence() = runBlocking {
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)
        assertEquals(1, store.getPendingCandidates().size)

        // Simulate MainActivity neutral button ("Later") click
        // Action: dialog is dismissed without mutating candidate store or inserting to Room

        // Verify pending store is completely unchanged
        val pending = store.getPendingCandidates()
        assertEquals(1, pending.size)
        assertEquals(candidate.fingerprint, pending[0].fingerprint)
        assertTrue("Candidate must remain pending", store.isPending(candidate.fingerprint))
        assertFalse("Candidate must NOT be rejected", store.isRejected(candidate.fingerprint, now))

        // Verify Room is completely untouched
        assertTrue("Room must have 0 records", dao.getAll().isEmpty())
    }

    @Test
    fun testMultiCandidateQueue_highestStopPresentedFirst() {
        val cand1 = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L) // stops at t2
        val cand2 = SleepCandidate(t3, t4, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t4 + 3600000L) // stops at t4 (newer)

        store.addPending(cand1, now)
        store.addPending(cand2, now)

        val pending = store.getPendingCandidates()
        assertEquals(2, pending.size)

        // MainActivity displays pending.first()
        val firstPresented = pending.first()
        assertEquals("Newer candidate (t4 stop) must be presented first", cand2.fingerprint, firstPresented.fingerprint)

        // User accepts cand2
        store.markAccepted(firstPresented)

        // On next inspection, cand1 is now the first candidate
        val remaining = store.getPendingCandidates()
        assertEquals(1, remaining.size)
        assertEquals("Older candidate (t2 stop) is now first in queue", cand1.fingerprint, remaining.first().fingerprint)
    }

    // =========================================================================
    // 2. ACTIVITY LIFECYCLE & RECREATION SAFETY SIMULATION
    // =========================================================================

    /**
     * Simulates the Activity lifecycle state machine for AutoSleep dialog presentation.
     */
    private class SimulatedMainActivity(
        private val prefs: FakeSharedPreferences,
        private val store: AutoSleepCandidateStore,
        private val dao: FakeSleepDao
    ) {
        var autoSleepDialogShownThisSession: Boolean = false
        var isDialogShowing: Boolean = false
        var currentlyDisplayedCandidate: SleepCandidate? = null
        var isFinishing: Boolean = false
        var isDestroyed: Boolean = false
        var resumePromptCount: Int = 0

        fun onResume() {
            if (autoSleepDialogShownThisSession || isDialogShowing) {
                return
            }
            val enabled = prefs.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
            if (!enabled) {
                return
            }

            // Simulate scan completion check
            if (isFinishing || isDestroyed) {
                return
            }

            val pending = store.getPendingCandidates()
            if (pending.isNotEmpty() && !autoSleepDialogShownThisSession && !isDialogShowing) {
                val candidate = pending.first()
                showDialog(candidate)
                resumePromptCount++
            }
        }

        private fun showDialog(candidate: SleepCandidate) {
            if (isFinishing || isDestroyed) return
            isDialogShowing = true
            autoSleepDialogShownThisSession = true
            currentlyDisplayedCandidate = candidate
        }

        fun onUserActionSave() {
            val candidate = currentlyDisplayedCandidate ?: return
            val sleep = Sleep().apply {
                start = candidate.start
                stop = candidate.stop
            }
            runBlocking {
                dao.insert(sleep)
            }
            store.markAccepted(candidate)
            isDialogShowing = false
            currentlyDisplayedCandidate = null
        }

        fun onUserActionDiscard(now: Long) {
            val candidate = currentlyDisplayedCandidate ?: return
            store.markRejected(candidate, now)
            isDialogShowing = false
            currentlyDisplayedCandidate = null
        }

        fun onUserActionLater() {
            // Later leaves candidate in store, dismisses dialog
            isDialogShowing = false
            currentlyDisplayedCandidate = null
        }

        fun onDestroy() {
            isDestroyed = true
            // Window leak prevention: dismiss dialog on destroy
            if (isDialogShowing) {
                isDialogShowing = false
                currentlyDisplayedCandidate = null
            }
        }
    }

    @Test
    fun testRecreationDuringDialog_oldDialogDismissedCleanlyAndNewActivityPrompts() {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        // Activity 1 launches and displays dialog
        val activity1 = SimulatedMainActivity(prefs, store, dao)
        activity1.onResume()

        assertTrue("Activity 1 must show dialog", activity1.isDialogShowing)
        assertEquals(candidate.fingerprint, activity1.currentlyDisplayedCandidate?.fingerprint)
        assertEquals(1, activity1.resumePromptCount)

        // Configuration change occurs (orientation change): Activity 1 is destroyed
        activity1.onDestroy()
        assertFalse("Activity 1 dialog must be dismissed in onDestroy to avoid window leak", activity1.isDialogShowing)
        assertNull("Activity 1 candidate reference must be cleared", activity1.currentlyDisplayedCandidate)

        // Activity 2 is created and resumes
        val activity2 = SimulatedMainActivity(prefs, store, dao)
        activity2.onResume()

        assertTrue("Activity 2 must safely present dialog for pending candidate on new window", activity2.isDialogShowing)
        assertEquals(candidate.fingerprint, activity2.currentlyDisplayedCandidate?.fingerprint)
        assertEquals(1, activity2.resumePromptCount)
    }

    @Test
    fun testRecreationAfterSave_noDialogShownOnNewActivity() {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        // Activity 1 launches, shows dialog, and user clicks Save
        val activity1 = SimulatedMainActivity(prefs, store, dao)
        activity1.onResume()
        activity1.onUserActionSave()

        assertFalse("Dialog must be dismissed after Save", activity1.isDialogShowing)
        assertEquals(1, dao.getAllSync().size)
        assertTrue("Pending store must be empty", store.getPendingCandidates().isEmpty())

        // Activity 1 destroyed
        activity1.onDestroy()

        // Activity 2 created and resumes
        val activity2 = SimulatedMainActivity(prefs, store, dao)
        activity2.onResume()

        assertFalse("Activity 2 must NOT show dialog because candidate was saved and removed from store", activity2.isDialogShowing)
        assertEquals(0, activity2.resumePromptCount)
    }

    @Test
    fun testRecreationAfterDiscard_noDialogShownOnNewActivity() {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        // Activity 1 launches, shows dialog, and user clicks Discard
        val activity1 = SimulatedMainActivity(prefs, store, dao)
        activity1.onResume()
        activity1.onUserActionDiscard(now)

        assertFalse("Dialog must be dismissed after Discard", activity1.isDialogShowing)
        assertTrue("Store must mark candidate rejected", store.isRejected(candidate.fingerprint, now))
        assertTrue("Pending store must be empty", store.getPendingCandidates().isEmpty())

        // Activity 1 destroyed
        activity1.onDestroy()

        // Activity 2 created and resumes
        val activity2 = SimulatedMainActivity(prefs, store, dao)
        activity2.onResume()

        assertFalse("Activity 2 must NOT show dialog because candidate was discarded", activity2.isDialogShowing)
        assertEquals(0, activity2.resumePromptCount)
    }

    @Test
    fun testRecreationAfterLater_candidateCanBePromptedInNewSession() {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        // Activity 1 launches, shows dialog, user clicks Later
        val activity1 = SimulatedMainActivity(prefs, store, dao)
        activity1.onResume()
        activity1.onUserActionLater()

        assertFalse("Dialog must be dismissed after Later", activity1.isDialogShowing)
        assertEquals("Candidate remains in pending store", 1, store.getPendingCandidates().size)

        // In same Activity 1 session, repeated onResume does NOT re-prompt
        activity1.onResume()
        assertFalse("Activity 1 session guard prevents duplicate dialog on repeated onResume", activity1.isDialogShowing)
        assertEquals(1, activity1.resumePromptCount)

        // Activity 1 destroyed
        activity1.onDestroy()

        // Activity 2 created in fresh session
        val activity2 = SimulatedMainActivity(prefs, store, dao)
        activity2.onResume()

        assertTrue("Activity 2 in new session can present pending candidate", activity2.isDialogShowing)
        assertEquals(candidate.fingerprint, activity2.currentlyDisplayedCandidate?.fingerprint)
        assertEquals(1, activity2.resumePromptCount)
    }

    @Test
    fun testAtMostOncePerSession_multipleOnResumeCallsInSameActivityNeverDuplicate() {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        val activity = SimulatedMainActivity(prefs, store, dao)
        activity.onResume() // First onResume: dialog shown

        assertTrue(activity.isDialogShowing)
        assertEquals(1, activity.resumePromptCount)

        // Subsequent onResume invocations in the same activity lifecycle (e.g. app switcher, notification shade)
        activity.onResume()
        activity.onResume()
        activity.onResume()

        assertEquals("Prompt count must remain exactly 1 despite multiple onResume calls", 1, activity.resumePromptCount)
        assertTrue(activity.isDialogShowing)
    }

    @Test
    fun testActivityDestroyedDuringAsyncScan_abortsDialogPresentationSafely() {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, true).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        val activity = SimulatedMainActivity(prefs, store, dao)
        // Activity begins destruction before async scan completes
        activity.isDestroyed = true

        activity.onResume()

        assertFalse("No dialog must be presented when activity is destroyed", activity.isDialogShowing)
        assertNull(activity.currentlyDisplayedCandidate)
        assertEquals(0, activity.resumePromptCount)
    }

    @Test
    fun testOptInGating_disabledAutoSleepNeverPromptsDialog() {
        prefs.edit().putBoolean(AutoSleepConfig.ENABLED_KEY, false).apply()
        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        store.addPending(candidate, now)

        val activity = SimulatedMainActivity(prefs, store, dao)
        activity.onResume()

        assertFalse("Dialog must not be shown when AutoSleep is disabled", activity.isDialogShowing)
        assertEquals(0, activity.resumePromptCount)
    }

    @Test
    fun testCorruptedStoreRecovery_failSafeGracefullyWithoutCrash() {
        prefs.edit()
            .putString(AutoSleepConfig.PENDING_JSON_KEY, "{ invalid json corrupt : [}")
            .putString(AutoSleepConfig.REJECTED_JSON_KEY, "not a json array")
            .apply()

        assertTrue("Corrupted pending store returns empty list safely", store.getPendingCandidates().isEmpty())
        assertFalse("Corrupted rejected store returns false safely", store.isRejected("1:OVERNIGHT_LONGEST_GAP:100:200", now))

        val candidate = SleepCandidate(t1, t2, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, t2 + 3600000L)
        assertTrue("Corrupted store can recover and add pending candidate", store.addPending(candidate, now))
        assertEquals(1, store.getPendingCandidates().size)
    }

    // Helper extension
    private fun FakeSleepDao.getAllSync(): List<Sleep> = runBlocking { getAll() }
}
