/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import hu.vmiklos.plees_tracker.DataModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodic WorkManager worker that triggers background sleep detection scans every 12 hours.
 */
class AutoSleepWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val context = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Ensure database and shared state are safely initialized
        DataModel.init(context, prefs)

        // Construct backend and execute scan
        val backend = AutoSleepBackend.create(context)
        val scanResult = backend.scan()

        Log.d(
            TAG,
            "AutoSleep background scan completed with status: ${scanResult.status} " +
                "(discovered=${scanResult.discovered}, queued=${scanResult.queued}, autoSaved=${scanResult.autoSaved})"
        )

        Result.success()
    } catch (e: CancellationException) {
        // Rethrow coroutine cancellation so WorkManager handles worker termination correctly
        throw e
    } catch (e: SecurityException) {
        Log.w(TAG, "AutoSleep background scan missing permission: ${e.message}")
        Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "AutoSleep background scan failed with unexpected exception", e)
        Result.success()
    }

    companion object {
        const val UNIQUE_WORK = "autosleep_periodic_scan"
        private const val TAG = "AutoSleepWorker"

        /**
         * Enqueues or updates the 12-hour unique periodic background scan.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoSleepWorker>(
                AutoSleepConfig.PERIODIC_WORK_HOURS,
                TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Cancels the unique periodic background scan.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
