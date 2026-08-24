/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

/**
 * Policy identifier for AutoSleep detection algorithms.
 */
enum class AutoSleepPolicyId {
    PURE_LONGEST_GAP,
    OVERNIGHT_LONGEST_GAP
}

/**
 * Value model representing an interval of device non-use between two consecutive unlock timestamps.
 *
 * @property start Epoch millisecond timestamp of the prior unlock.
 * @property stop Epoch millisecond timestamp of the subsequent unlock.
 */
data class UnlockGap(
    val start: Long,
    val stop: Long
) {
    /**
     * True elapsed physical duration in milliseconds.
     */
    val durationMs: Long get() = stop - start
}

/**
 * Immutable detected sleep candidate model with deterministic string fingerprint.
 *
 * @property start Epoch millisecond timestamp of sleep onset.
 * @property stop Epoch millisecond timestamp of sleep termination (wake).
 * @property policy Detection heuristic policy used to discover this interval.
 * @property generatedAt Epoch millisecond timestamp when the candidate was computed.
 * @property detectorVersion Schema version of the detection algorithm (default 1).
 */
data class SleepCandidate(
    val start: Long,
    val stop: Long,
    val policy: AutoSleepPolicyId,
    val generatedAt: Long,
    val detectorVersion: Int = 1
) {
    init {
        require(stop > start) { "stop ($stop) must be greater than start ($start)" }
    }

    /**
     * True elapsed physical duration in milliseconds.
     */
    val durationMs: Long get() = stop - start

    /**
     * Deterministic, collision-resistant string fingerprint for candidate deduplication,
     * rejection tracking, and state persistence.
     *
     * Format: "$detectorVersion:${policy.name}:$start:$stop"
     */
    val fingerprint: String get() = "$detectorVersion:${policy.name}:$start:$stop"
}
