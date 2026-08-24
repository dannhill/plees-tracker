/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isolated unit test suite for overlap calculation, boundary clipping, interval merging,
 * and suppression threshold precision (UT-BE-006, UT-BE-007, UT-BE-008).
 */
class AutoSleepOverlapTest {

    /**
     * Baseline: Zero existing sleeps -> 0.0 coverage
     */
    @Test
    fun testZeroOverlapCoverage() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L)
        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, emptyList())
        assertEquals(0.0, coverage, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, emptyList()))
    }

    /**
     * Baseline: Sleep completely before or completely after candidate window
     */
    @Test
    fun testDisjointSleepsOutsideCandidateWindow() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L)
        val sleepBefore = Sleep().apply { start = 1000L; stop = 9000L }
        val sleepAfter = Sleep().apply { start = 21000L; stop = 30000L }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleepBefore, sleepAfter))
        assertEquals(0.0, coverage, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleepBefore, sleepAfter)))
    }

    /**
     * Baseline: Contiguous touching boundary endpoints (0 ms overlap)
     */
    @Test
    fun testContiguousTouchingBoundaryEndpoints() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L)
        val sleepTouchingBefore = Sleep().apply { start = 5000L; stop = 10000L }
        val sleepTouchingAfter = Sleep().apply { start = 20000L; stop = 25000L }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(
            candidate,
            listOf(sleepTouchingBefore, sleepTouchingAfter)
        )
        assertEquals(0.0, coverage, 0.0001)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleepTouchingBefore, sleepTouchingAfter)))
    }

    /**
     * Clipping: Sleep extends before and after candidate (100% enclosed)
     */
    @Test
    fun testSleepEnclosingCandidateClippedTo100Percent() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L)
        val enclosingSleep = Sleep().apply { start = 5000L; stop = 25000L }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(enclosingSleep))
        assertEquals(1.0, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(enclosingSleep)))
    }

    /**
     * UT-BE-006: Overlap coverage 49.0% -> NOT suppressed
     */
    @Test
    fun testUT_BE_006_overlapCoverage49PercentNotSuppressed() {
        val candidate = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L, // 8h = 28,800,000 ms
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_028_800_000L
        )

        // 49.0% of 28,800,000 ms = 14,112,000 ms (3.92h)
        val coveredMs = (28_800_000L * 0.49).toLong()
        val sleep = Sleep().apply {
            start = candidate.start
            stop = candidate.start + coveredMs
        }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep))
        assertEquals(0.49, coverage, 0.0001)
        assertFalse("49.0% coverage must NOT be suppressed", AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep)))
    }

    /**
     * High-precision edge case: 49.999% coverage -> NOT suppressed
     */
    @Test
    fun testOverlapCoverage49Point999PercentNotSuppressed() {
        val candidate = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_028_800_000L
        )

        // 14,399,999 ms / 28,800,000 ms = 49.9999965%
        val sleep = Sleep().apply {
            start = candidate.start
            stop = candidate.start + 14_399_999L
        }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep))
        assertTrue("Coverage must be strictly < 0.50", coverage < 0.50)
        assertFalse(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep)))
    }

    /**
     * UT-BE-007: Overlap coverage 50.0% -> SUPPRESSED
     */
    @Test
    fun testUT_BE_007_overlapCoverage50PercentSuppressed() {
        val candidate = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L, // 8h = 28,800,000 ms
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_028_800_000L
        )

        // Exactly 50.0% = 14,400,000 ms (4.0h)
        val sleep = Sleep().apply {
            start = candidate.start
            stop = candidate.start + 14_400_000L
        }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep))
        assertEquals(0.50, coverage, 0.0001)
        assertTrue("Exact 50.0% coverage MUST be suppressed", AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep)))
    }

    /**
     * High-precision edge case: 50.001% coverage -> SUPPRESSED
     */
    @Test
    fun testOverlapCoverage50Point001PercentSuppressed() {
        val candidate = SleepCandidate(
            start = 1_000_000_000L,
            stop = 1_028_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 1_028_800_000L
        )

        // 14,400,001 ms / 28,800,000 ms = 50.0000035%
        val sleep = Sleep().apply {
            start = candidate.start
            stop = candidate.start + 14_400_001L
        }

        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(sleep))
        assertTrue("50.001% coverage must be >= 0.50", coverage >= 0.50)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(sleep)))
    }

    /**
     * UT-BE-008: Multiple fragmented Room sleeps merging to >50% -> SUPPRESSED
     */
    @Test
    fun testUT_BE_008_multipleSleepsMergeCoverageSuppression() {
        // Candidate: 00:00 to 08:00 (8h = 28,800,000 ms)
        val candidate = SleepCandidate(
            start = 0L,
            stop = 28_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 28_800_000L
        )

        // Sleep 1: 01:00 to 03:30 (2.5h) -> [3,600,000 .. 12,600,000]
        val s1 = Sleep().apply {
            start = 3_600_000L
            stop = 12_600_000L
        }

        // Sleep 2: 03:00 to 06:00 (3.0h) -> [10,800,000 .. 21,600,000]
        // Overlap with Sleep 1: 03:00 to 03:30
        val s2 = Sleep().apply {
            start = 10_800_000L
            stop = 21_600_000L
        }

        // Merged interval: [3,600,000 .. 21,600,000] = 5.0h (18,000,000 ms)
        // Ratio = 18,000,000 / 28,800,000 = 5 / 8 = 0.625 (62.5%)
        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(s1, s2))
        assertEquals(0.625, coverage, 0.0001)
        assertTrue("62.5% merged coverage MUST be suppressed", AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(s1, s2)))
    }

    /**
     * Multiple disjoint sleeps correctly merged without losing subsequent intervals
     */
    @Test
    fun testMultipleDisjointSleepsMergeCorrectly() {
        // Candidate: 00:00 to 08:00 (8h = 28,800,000 ms)
        val candidate = SleepCandidate(
            start = 0L,
            stop = 28_800_000L,
            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
            generatedAt = 28_800_000L
        )

        // Sleep 1: 01:00 to 03:00 (2h = 7,200,000 ms)
        val s1 = Sleep().apply {
            start = 3_600_000L
            stop = 10_800_000L
        }

        // Sleep 2: 05:00 to 08:00 (3h = 10,800,000 ms)
        val s2 = Sleep().apply {
            start = 18_000_000L
            stop = 28_800_000L
        }

        // Total covered: 2h + 3h = 5h (18,000,000 ms) -> 5/8 = 62.5%
        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(s1, s2))
        assertEquals(0.625, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(s1, s2)))
    }

    /**
     * Boundary clipping with multiple external sleeps
     */
    @Test
    fun testMultipleSleepsClippingAndMerging() {
        val candidate = SleepCandidate(10_000L, 20_000L, AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP, 20_000L) // duration 10,000 ms

        // s1: starts at 5,000, ends at 12,000 (clipped: 10,000..12,000 = 2,000 ms)
        val s1 = Sleep().apply { start = 5000L; stop = 12000L }

        // s2: starts at 12,000, ends at 14,000 (contiguous with s1, clipped: 12,000..14,000 = 2,000 ms)
        val s2 = Sleep().apply { start = 12000L; stop = 14000L }

        // s3: starts at 17,000, ends at 25,000 (clipped: 17,000..20,000 = 3,000 ms)
        val s3 = Sleep().apply { start = 17000L; stop = 25000L }

        // Merged: [10,000..14,000] (4,000 ms) + [17,000..20,000] (3,000 ms) = 7,000 ms (70%)
        val coverage = AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, listOf(s1, s2, s3))
        assertEquals(0.70, coverage, 0.0001)
        assertTrue(AutoSleepOverlapCalculator.isSuppressed(candidate, listOf(s1, s2, s3)))
    }
}
