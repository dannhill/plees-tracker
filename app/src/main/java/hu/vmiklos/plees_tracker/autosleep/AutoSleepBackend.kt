/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import hu.vmiklos.plees_tracker.DataModel
import hu.vmiklos.plees_tracker.Sleep
import hu.vmiklos.plees_tracker.SleepDao
import java.util.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Result status of an AutoSleep scan execution.
 */
enum class ScanStatus {
    SUCCESS,
    DISABLED,
    UNSUPPORTED,
    NO_PERMISSION,
    EMPTY
}

/**
 * Scan summary report returned by [AutoSleepBackend].
 *
 * @property status Execution status code.
 * @property discovered Total number of candidates discovered by the detector.
 * @property queued Number of candidates added to pending storage for user confirmation.
 * @property autoSaved Number of candidates automatically inserted into the Room database.
 */
data class ScanResult(
    val status: ScanStatus,
    val discovered: Int = 0,
    val queued: Int = 0,
    val autoSaved: Int = 0
)

/**
 * Backend coordinator orchestrating permission checks, event retrieval, detection,
 * overlap suppression, and candidate persistence.
 *
 * Protected by process-wide [Mutex] concurrency serialization to prevent race conditions
 * between foreground UI scans and periodic background WorkManager executions.
 */
class AutoSleepBackend(
    private val preferences: SharedPreferences,
    private val eventSource: UnlockEventSource,
    private val store: AutoSleepCandidateStore,
    private val detector: AutoSleepDetector,
    private val sleepDao: SleepDao,
    private val context: Context? = null,
    private val isSupportedProvider: () -> Boolean = { UsageAccess.isSupported() },
    private val hasPermissionProvider: () -> Boolean = {
        context?.let { UsageAccess.hasAccess(it) } ?: true
    },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.getDefault() },
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val onAutoSaved: (suspend (Sleep) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AutoSleepBackend"
        private val scanMutex = Mutex()

        /**
         * Factory method to create an [AutoSleepBackend] instance configured with Android platform components.
         */
        fun create(context: Context): AutoSleepBackend {
            val appContext = context.applicationContext ?: context
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            DataModel.init(appContext, prefs)
            val eventSource = AndroidUnlockEventSource(appContext)
            val store = AutoSleepCandidateStore(prefs)
            val detector = AutoSleepDetector()
            val sleepDao = DataModel.database.sleepDao()

            return AutoSleepBackend(
                preferences = prefs,
                eventSource = eventSource,
                store = store,
                detector = detector,
                sleepDao = sleepDao,
                context = appContext,
                isSupportedProvider = { UsageAccess.isSupported() },
                hasPermissionProvider = { UsageAccess.hasAccess(appContext) },
                timeZoneProvider = { TimeZone.getDefault() },
                nowProvider = { System.currentTimeMillis() },
                onAutoSaved = {
                    try {
                        DataModel.scheduleHealthConnectSync()
                        DataModel.backupSleeps(appContext, appContext.contentResolver)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to schedule sync or backup after auto-save", e)
                    }
                }
            )
        }
    }

    /**
     * Executes the AutoSleep scan pipeline under Mutex concurrency lock.
     *
     * @return [ScanResult] detailing execution status and counts.
     */
    suspend fun scan(): ScanResult = scanMutex.withLock {
        // 1. Check feature enabled preference
        val enabled = preferences.getBoolean(AutoSleepConfig.ENABLED_KEY, false)
        if (!enabled) {
            return ScanResult(status = ScanStatus.DISABLED)
        }

        // 2. Check platform support (API >= 28)
        if (!isSupportedProvider()) {
            return ScanResult(status = ScanStatus.UNSUPPORTED)
        }

        // 3. Check usage access permission (PACKAGE_USAGE_STATS)
        if (!hasPermissionProvider()) {
            return ScanResult(status = ScanStatus.NO_PERMISSION)
        }

        // 4. Retrieve unlock events within 72-hour lookback window
        val now = nowProvider()
        val begin = now - AutoSleepConfig.LOOKBACK_HOURS * 3600 * 1000L
        val unlocks = eventSource.getUnlockTimestamps(begin, now)

        // 5. Parse policy preference and execute detection
        val policyName = preferences.getString(
            AutoSleepConfig.POLICY_KEY,
            AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name
        ) ?: AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP.name
        val policy = try {
            AutoSleepPolicyId.valueOf(policyName)
        } catch (_: Exception) {
            AutoSleepPolicyId.OVERNIGHT_LONGEST_GAP
        }

        val timeZone = timeZoneProvider()
        val candidates = detector.detect(unlocks, policy, now, timeZone)
        if (candidates.isEmpty()) {
            return ScanResult(status = ScanStatus.EMPTY)
        }

        // 6. Evaluate candidates against store deduplication and Room overlap suppression
        val saveMode = preferences.getString(AutoSleepConfig.SAVE_MODE_KEY, "SUGGEST") ?: "SUGGEST"
        var queued = 0
        var autoSaved = 0

        for (candidate in candidates) {
            // Deduplication & Rejection checks
            if (store.isPending(candidate.fingerprint) || store.isRejected(candidate.fingerprint, now)) {
                continue
            }

            // Room overlap suppression check
            val overlappingSleeps = sleepDao.getOverlapping(candidate.start, candidate.stop)
            if (isCandidateSuppressed(candidate, overlappingSleeps)) {
                continue
            }

            // Save mode execution
            if (saveMode == "AUTO_SAVE") {
                val sleep = Sleep().apply {
                    start = candidate.start
                    stop = candidate.stop
                }
                sleepDao.insert(sleep)
                store.markAccepted(candidate)
                autoSaved++

                // Trigger persistence pipeline (Health Connect & Backups)
                onAutoSaved?.invoke(sleep)
            } else {
                if (store.addPending(candidate, now)) {
                    queued++
                }
            }
        }

        ScanResult(
            status = ScanStatus.SUCCESS,
            discovered = candidates.size,
            queued = queued,
            autoSaved = autoSaved
        )
    }

    /**
     * Calculates the overlap coverage ratio of [candidate] against [existingSleeps].
     */
    fun calculateOverlapCoverage(candidate: SleepCandidate, existingSleeps: List<Sleep>): Double {
        return AutoSleepOverlapCalculator.calculateOverlapCoverage(candidate, existingSleeps)
    }

    /**
     * Evaluates whether [candidate] should be suppressed based on coverage threshold (>= 50%).
     */
    fun isCandidateSuppressed(
        candidate: SleepCandidate,
        existingSleeps: List<Sleep>,
        threshold: Double = AutoSleepConfig.OVERLAP_SUPPRESSION_THRESHOLD
    ): Boolean {
        return AutoSleepOverlapCalculator.isSuppressed(candidate, existingSleeps, threshold)
    }
}
