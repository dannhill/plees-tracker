/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Isolated unit test suite for AutoSleepCandidateStore.
 * Tests JSON serialization, pending deduplication (UT-BE-004), rejection tracking,
 * 96h TTL expiration (UT-BE-005), capacity bounds, and corrupted JSON resilience.
 */
class AutoSleepCandidateStoreTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: AutoSleepCandidateStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = AutoSleepCandidateStore(prefs)
    }

    /**
     * UT-BE-004: Deduplication in pending candidate store
     */
    @Test
    fun testUT_BE_004_existingPendingDeduplicated() {
        val candidate1 = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_028_900_000L,
            detectorVersion = 1
        )
        val added1 = store.addPending(candidate1)
        assertTrue("First insertion of candidate must succeed", added1)
        assertEquals(1, store.getPendingCandidates().size)

        // Attempt to add duplicate candidate with identical fingerprint but different generatedAt
        val candidate2 = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_029_000_000L,
            detectorVersion = 1
        )
        assertEquals(candidate1.fingerprint, candidate2.fingerprint)
        assertTrue("Candidate must be recognized as pending", store.isPending(candidate2.fingerprint))

        val added2 = store.addPending(candidate2)
        assertFalse("Duplicate candidate addition must return false", added2)
        assertEquals("Pending store count must remain 1", 1, store.getPendingCandidates().size)
    }

    /**
     * UT-BE-005: Suppression of candidate in rejected store within 96h TTL and purging after 96h
     */
    @Test
    fun testUT_BE_005_rejectedCandidateSuppressedWith96hTtl() {
        val candidate = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_028_900_000L
        )
        val rejectTime = 1_030_000_000L
        val ttlMs = AutoSleepConfig.REJECTED_TTL_HOURS * 3600 * 1000L // 96h = 345,600,000 ms

        store.addPending(candidate)
        assertEquals(1, store.getPendingCandidates().size)

        // Mark rejected
        store.markRejected(candidate, rejectTime)
        assertTrue("Pending candidate must be removed upon rejection", store.getPendingCandidates().isEmpty())

        // 1. Check immediately
        assertTrue("Must be rejected at reject time", store.isRejected(candidate.fingerprint, rejectTime))

        // 2. Check at 48 hours (within TTL)
        val query48h = rejectTime + 48 * 3600 * 1000L
        assertTrue("Must remain rejected at 48h", store.isRejected(candidate.fingerprint, query48h))
        assertFalse("Adding rejected candidate must return false", store.addPending(candidate, query48h))

        // 3. Check at 95h 59m (boundary before expiration)
        val query95h = rejectTime + ttlMs - 60_000L
        assertTrue("Must remain rejected just before 96h", store.isRejected(candidate.fingerprint, query95h))

        // 4. Check at 96h + 1 second (expired TTL)
        val queryExpired = rejectTime + ttlMs + 1000L
        assertFalse("Must NOT be rejected after 96h TTL expires", store.isRejected(candidate.fingerprint, queryExpired))

        // 5. Store allows re-adding once TTL expires
        val reAdded = store.addPending(candidate, queryExpired)
        assertTrue("Re-adding candidate after rejection TTL expiration must succeed", reAdded)
        assertEquals(1, store.getPendingCandidates().size)
    }

    /**
     * Capacity enforcement: MAX_PENDING = 7, sorted by stop descending
     */
    @Test
    fun testCandidateStoreMaxPendingCappedAt7() {
        for (i in 1..10) {
            val start = i * 10_000_000L
            val stop = start + 5_000_000L
            store.addPending(SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop))
        }

        val pending = store.getPendingCandidates()
        assertEquals("Pending store must be capped at MAX_PENDING (7)", AutoSleepConfig.MAX_PENDING, pending.size)
        // Verify sorted descending by stop
        for (i in 0 until pending.size - 1) {
            assertTrue("Pending candidates must be sorted descending by stop", pending[i].stop >= pending[i + 1].stop)
        }
        // Most recent stop (from i=10) must be first
        assertEquals(10 * 10_000_000L + 5_000_000L, pending[0].stop)
    }

    /**
     * Capacity enforcement: MAX_REJECTED = 30
     */
    @Test
    fun testCandidateStoreMaxRejectedCappedAt30() {
        val now = 1_000_000_000L
        for (i in 1..40) {
            val start = i * 10_000_000L
            val stop = start + 5_000_000L
            val candidate = SleepCandidate(start, stop, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, stop)
            store.markRejected(candidate, now + i * 1000L)
        }

        val c40 = SleepCandidate(40 * 10_000_000L, 40 * 10_000_000L + 5_000_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 0L)
        val c1 = SleepCandidate(1 * 10_000_000L, 1 * 10_000_000L + 5_000_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 0L)

        val queryTime = now + 50_000L
        assertTrue("Newest rejected candidate must be retained", store.isRejected(c40.fingerprint, queryTime))
        assertFalse("Oldest candidate beyond MAX_REJECTED (30) must be evicted", store.isRejected(c1.fingerprint, queryTime))
    }

    /**
     * Mark accepted removes candidate from pending list
     */
    @Test
    fun testCandidateStoreMarkAcceptedRemovesPending() {
        val candidate = SleepCandidate(100L, 200L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 300L)
        store.addPending(candidate)
        assertTrue(store.isPending(candidate.fingerprint))

        store.markAccepted(candidate)
        assertFalse(store.isPending(candidate.fingerprint))
        assertTrue(store.getPendingCandidates().isEmpty())
    }

    /**
     * Clear clears all pending and rejected candidates
     */
    @Test
    fun testCandidateStoreClear() {
        val candidate = SleepCandidate(100L, 200L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 300L)
        store.addPending(candidate)
        store.markRejected(candidate, 400L)

        store.clear()
        assertTrue(store.getPendingCandidates().isEmpty())
        assertFalse(store.isRejected(candidate.fingerprint, 400L))
    }

    /**
     * Resilient JSON corruption handling (STORE-001, STORE-002)
     */
    @Test
    fun testCandidateStoreCorruptJsonFailSafe() {
        prefs.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, "{malformed_json: unclosed [").apply()
        prefs.edit().putString(AutoSleepConfig.REJECTED_JSON_KEY, "invalid_json_array").apply()

        // Should return empty lists without throwing JSONException
        val pending = store.getPendingCandidates()
        assertTrue("Corrupted pending JSON must return empty list safely", pending.isEmpty())
        assertFalse("Corrupted rejected JSON must return false safely", store.isRejected("1:OVERNIGHT_LONGEST_GAP:100:200"))

        // Next valid write recovers clean state
        val validCandidate = SleepCandidate(100L, 200L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 300L)
        val added = store.addPending(validCandidate)
        assertTrue("Writing after corruption must recover and succeed", added)
        assertEquals(1, store.getPendingCandidates().size)
    }

    /**
     * Corrupted or missing fields in JSON objects
     */
    @Test
    fun testCandidateStoreCorruptFieldsHandledSafely() {
        // Missing "policy" field
        prefs.edit().putString(
            AutoSleepConfig.PENDING_JSON_KEY,
            """[{"start": 100, "stop": 200, "generatedAt": 300}]"""
        ).apply()

        val pending = store.getPendingCandidates()
        assertTrue("JSON missing required fields must return empty list safely", pending.isEmpty())
    }
}
