/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import hu.vmiklos.plees_tracker.Sleep

/**
 * Pure, deterministic utility for calculating overlap coverage between detected sleep candidates
 * and existing manual or auto-saved sleep records in the Room database.
 */
object AutoSleepOverlapCalculator {

    /**
     * Calculates the fraction of [candidate] interval covered by [existingSleeps].
     *
     * Performs boundary clipping, interval sorting, overlapping/contiguous interval merging,
     * and duration summation to compute the precise coverage ratio.
     *
     * @param candidate The detected sleep candidate under evaluation.
     * @param existingSleeps The list of existing Sleep records (e.g. from SleepDao.getOverlapping).
     * @return Double in the range [0.0, 1.0] representing the covered proportion.
     */
    fun calculateOverlapCoverage(candidate: SleepCandidate, existingSleeps: List<Sleep>): Double {
        if (existingSleeps.isEmpty() || candidate.durationMs <= 0L) {
            return 0.0
        }

        // 1. Clip existing sleep intervals to candidate bounds [candidate.start, candidate.stop]
        val clipped = existingSleeps.mapNotNull { sleep ->
            val clipStart = maxOf(sleep.start, candidate.start)
            val clipStop = minOf(sleep.stop, candidate.stop)
            if (clipStop > clipStart) {
                clipStart to clipStop
            } else {
                null
            }
        }.sortedBy { it.first }

        if (clipped.isEmpty()) {
            return 0.0
        }

        // 2. Merge overlapping or contiguous clipped intervals
        val merged = mutableListOf<Pair<Long, Long>>()
        var current = clipped[0]

        for (i in 1 until clipped.size) {
            val next = clipped[i]
            if (next.first <= current.second) {
                current = current.first to maxOf(current.second, next.second)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        // 3. Sum total covered duration across merged disjoint intervals
        val totalCoveredMs = merged.sumOf { it.second - it.first }

        // 4. Compute coverage ratio
        return totalCoveredMs.toDouble() / candidate.durationMs.toDouble()
    }

    /**
     * Determines whether [candidate] should be suppressed given [existingSleeps].
     *
     * @param candidate The detected sleep candidate under evaluation.
     * @param existingSleeps The list of existing Sleep records.
     * @param threshold Overlap ratio threshold (default: 0.50 / 50%).
     * @return True if coverage ratio >= threshold, false otherwise.
     */
    fun isSuppressed(
        candidate: SleepCandidate,
        existingSleeps: List<Sleep>,
        threshold: Double = AutoSleepConfig.OVERLAP_SUPPRESSION_THRESHOLD
    ): Boolean {
        return calculateOverlapCoverage(candidate, existingSleeps) >= threshold
    }
}
