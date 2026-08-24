/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import hu.vmiklos.plees_tracker.Sleep
import hu.vmiklos.plees_tracker.SleepDao

/**
 * In-memory SleepDao implementation for unit and E2E testing.
 */
class FakeSleepDao : SleepDao {
    private val sleeps = mutableListOf<Sleep>()
    private var nextId = 1
    private val lock = Any()

    override suspend fun getAll(): List<Sleep> = synchronized(lock) {
        sleeps.sortedBy { it.sid }.toList()
    }

    override suspend fun getPendingHealthConnectWrites(): List<Sleep> = synchronized(lock) {
        sleeps.filter { it.healthConnectSyncedVersion != it.healthConnectVersion }
            .sortedBy { it.sid }
    }

    override suspend fun hasPendingHealthConnectWrites(after: Long): Boolean = synchronized(lock) {
        sleeps.any {
            it.healthConnectSyncedVersion != it.healthConnectVersion &&
                it.stop > it.start &&
                it.start >= after
        }
    }

    override fun getAllLive(): LiveData<List<Sleep>> = MutableLiveData(getAllSync())

    override suspend fun getById(id: Int): Sleep = synchronized(lock) {
        sleeps.first { it.sid == id }
    }

    override suspend fun getByIdOrNull(id: Int): Sleep? = synchronized(lock) {
        sleeps.firstOrNull { it.sid == id }
    }

    override fun getAfterLive(after: Long): LiveData<List<Sleep>> = synchronized(lock) {
        MutableLiveData(sleeps.filter { it.stop > after }.sortedByDescending { it.start })
    }

    override suspend fun insert(sleepList: List<Sleep>) {
        synchronized(lock) {
            for (s in sleepList) {
                insertInternal(s)
            }
        }
    }

    override suspend fun insert(sleep: Sleep): Long = synchronized(lock) {
        insertInternal(sleep)
    }

    private fun insertInternal(sleep: Sleep): Long {
        if (sleep.sid == 0) {
            sleep.sid = nextId++
        }
        sleeps.add(sleep)
        return sleep.sid.toLong()
    }

    override suspend fun update(sleep: Sleep): Unit = synchronized(lock) {
        val idx = sleeps.indexOfFirst { it.sid == sleep.sid }
        if (idx >= 0) {
            sleeps[idx] = sleep
        }
    }

    override suspend fun delete(sleep: Sleep): Unit = synchronized(lock) {
        sleeps.removeAll { it.sid == sleep.sid }
    }

    override suspend fun deleteAll(): Unit = synchronized(lock) {
        sleeps.clear()
    }

    override suspend fun count(): Int = synchronized(lock) {
        sleeps.size
    }

    /**
     * M3 Extension contract query: returns sleeps overlapping [start, stop].
     * start_date < :stop AND stop_date > :start ORDER BY start_date ASC
     */
    override suspend fun getOverlapping(start: Long, stop: Long): List<Sleep> = synchronized(lock) {
        sleeps.filter { it.start < stop && it.stop > start }
            .sortedBy { it.start }
    }

    private fun getAllSync(): List<Sleep> = synchronized(lock) {
        sleeps.sortedByDescending { it.start }.toList()
    }
}
