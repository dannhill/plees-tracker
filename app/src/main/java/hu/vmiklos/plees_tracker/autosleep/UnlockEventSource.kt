/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

/**
 * Abstraction interface for retrieving device unlock timestamps.
 *
 * Decouples detection logic from the Android runtime framework, enabling deterministic
 * local unit testing via mock/fake event sources.
 */
interface UnlockEventSource {
    /**
     * Queries device unlock timestamps within the interval [beginInclusive, endExclusive).
     *
     * @param beginInclusive Epoch millisecond timestamp of window start (inclusive).
     * @param endExclusive Epoch millisecond timestamp of window end (exclusive).
     * @return List of unlock event timestamps in epoch milliseconds.
     */
    suspend fun getUnlockTimestamps(beginInclusive: Long, endExclusive: Long): List<Long>
}
