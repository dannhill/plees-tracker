/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

/**
 * Configuration constants, preference keys, and default thresholds for AutoSleep.
 */
object AutoSleepConfig {
    const val ENABLED_KEY = "auto_sleep_enabled"
    const val SAVE_MODE_KEY = "auto_sleep_save_mode"
    const val POLICY_KEY = "auto_sleep_policy"
    const val PENDING_JSON_KEY = "auto_sleep_pending_candidates"
    const val REJECTED_JSON_KEY = "auto_sleep_rejected_candidates"

    const val LOOKBACK_HOURS = 72L
    const val REJECTED_TTL_HOURS = 96L
    const val PERIODIC_WORK_HOURS = 12L
    const val MAX_PENDING = 7
    const val MAX_REJECTED = 30

    const val MIN_OVERNIGHT_GAP_MINUTES = 180L       // 3 hours
    const val MAX_OVERNIGHT_GAP_MINUTES = 16L * 60L  // 16 hours
    const val ANCHOR_START_HOUR = 0                  // 00:00 local
    const val ANCHOR_END_HOUR = 6                    // 06:00 local
    const val OVERLAP_SUPPRESSION_THRESHOLD = 0.50   // 50%
}
