/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure Kotlin detection engine implementing event normalization and policy detection.
 */
class AutoSleepDetector(
    private val minOvernightGapMinutes: Long = AutoSleepConfig.MIN_OVERNIGHT_GAP_MINUTES,
    private val maxOvernightGapMinutes: Long = AutoSleepConfig.MAX_OVERNIGHT_GAP_MINUTES,
    private val anchorStartHour: Int = AutoSleepConfig.ANCHOR_START_HOUR,
    private val anchorEndHour: Int = AutoSleepConfig.ANCHOR_END_HOUR
) {
    /**
     * Normalizes raw unlock timestamps into consecutive intervals of device non-use.
     * Filters non-positive and future timestamps, deduplicates, sorts ascending, and generates consecutive intervals.
     */
    fun normalizeUnlocks(unlocks: List<Long>, now: Long): List<UnlockGap> {
        val normalized = unlocks
            .asSequence()
            .filter { it > 0L && it <= now }
            .distinct()
            .sorted()
            .toList()

        if (normalized.size < 2) return emptyList()

        return (0 until normalized.size - 1).map { i ->
            UnlockGap(start = normalized[i], stop = normalized[i + 1])
        }
    }

    /**
     * Executes sleep candidate detection using the specified policy heuristic.
     */
    fun detect(
        unlocks: List<Long>,
        policy: AutoSleepPolicyId,
        now: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<SleepCandidate> {
        val gaps = normalizeUnlocks(unlocks, now)
        if (gaps.isEmpty()) return emptyList()

        return when (policy) {
            AutoSleepPolicyId.PURE_LONGEST_GAP -> {
                val winner = gaps.maxWithOrNull(
                    compareBy<UnlockGap> { it.durationMs }.thenBy { it.stop }
                ) ?: return emptyList()

                listOf(
                    SleepCandidate(
                        start = winner.start,
                        stop = winner.stop,
                        policy = AutoSleepPolicyId.PURE_LONGEST_GAP,
                        generatedAt = now
                    )
                )
            }
            AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP -> {
                val minDurationMs = minOvernightGapMinutes * 60 * 1000L
                val maxDurationMs = maxOvernightGapMinutes * 60 * 1000L

                val eligible = gaps.filter { gap ->
                    if (gap.durationMs < minDurationMs || gap.durationMs > maxDurationMs) {
                        return@filter false
                    }

                    val cal = Calendar.getInstance(timeZone).apply {
                        timeInMillis = gap.stop
                    }
                    val wakeYear = cal.get(Calendar.YEAR)
                    val wakeMonth = cal.get(Calendar.MONTH)
                    val wakeDay = cal.get(Calendar.DAY_OF_MONTH)

                    val anchorCal = Calendar.getInstance(timeZone).apply {
                        set(Calendar.YEAR, wakeYear)
                        set(Calendar.MONTH, wakeMonth)
                        set(Calendar.DAY_OF_MONTH, wakeDay)
                        set(Calendar.HOUR_OF_DAY, anchorStartHour)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val anchorStart = anchorCal.timeInMillis

                    anchorCal.set(Calendar.HOUR_OF_DAY, anchorEndHour)
                    val anchorEnd = anchorCal.timeInMillis

                    gap.start < anchorEnd && gap.stop > anchorStart
                }

                if (eligible.isEmpty()) return emptyList()

                val groupedByWakeDate = eligible.groupBy { gap ->
                    val cal = Calendar.getInstance(timeZone).apply {
                        timeInMillis = gap.stop
                    }
                    "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
                }

                groupedByWakeDate.values.mapNotNull { dayGaps ->
                    dayGaps.maxWithOrNull(
                        compareBy<UnlockGap> { it.durationMs }.thenBy { it.stop }
                    )?.let { winner ->
                        SleepCandidate(
                            start = winner.start,
                            stop = winner.stop,
                            policy = AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP,
                            generatedAt = now
                        )
                    }
                }.sortedByDescending { it.stop }
            }
        }
    }
}
