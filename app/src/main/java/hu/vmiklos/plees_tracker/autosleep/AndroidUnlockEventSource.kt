/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android platform implementation of [UnlockEventSource] querying [UsageStatsManager].
 *
 * Extracts device unlock timestamps corresponding to [UsageEvents.Event.KEYGUARD_HIDDEN]
 * on Android 9+ (API level 28 / [Build.VERSION_CODES.P]).
 *
 * Privacy & Security Guarantees:
 * - Strictly local execution: zero network transmission.
 * - Zero persistence of raw app usage events: package names, class names, and application
 *   interaction history are never logged, cached, or stored on disk.
 * - Transient processing: Only filtered unlock timestamps (Long epoch ms) are held in memory
 *   during query execution.
 */
class AndroidUnlockEventSource(private val context: Context) : UnlockEventSource {

    @SuppressLint("MissingPermission")
    override suspend fun getUnlockTimestamps(
        beginInclusive: Long,
        endExclusive: Long
    ): List<Long> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return@withContext emptyList()
        }

        if (beginInclusive >= endExclusive) {
            return@withContext emptyList()
        }

        val appContext = context.applicationContext ?: context
        val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return@withContext emptyList()

        val events: UsageEvents? = try {
            usageStatsManager.queryEvents(beginInclusive, endExclusive)
        } catch (e: SecurityException) {
            return@withContext emptyList()
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        if (events == null) {
            return@withContext emptyList()
        }

        val unlockTimestamps = ArrayList<Long>()
        val reusableEvent = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(reusableEvent)
            if (reusableEvent.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                unlockTimestamps.add(reusableEvent.timeStamp)
            }
        }

        unlockTimestamps
    }
}
