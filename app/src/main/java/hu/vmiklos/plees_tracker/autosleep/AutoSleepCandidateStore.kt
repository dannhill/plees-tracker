/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed JSON persistence store for pending sleep candidates and rejected candidate fingerprints.
 *
 * Implements strict capacity bounds (MAX_PENDING = 7, MAX_REJECTED = 30), 96h TTL expiration for rejected
 * records, and fail-safe recovery for malformed or corrupted JSON.
 *
 * @param preferences SharedPreferences instance used to persist JSON strings.
 */
class AutoSleepCandidateStore(private val preferences: SharedPreferences) {

    /**
     * Lightweight internal representation for rejected candidate records.
     */
    private data class RejectedRecord(
        val fingerprint: String,
        val rejectedAt: Long
    )

    /**
     * Retrieves all pending sleep candidates, ordered by stop timestamp descending.
     *
     * @return List of pending candidates, or empty list if none exist or if stored JSON is corrupt.
     */
    fun getPendingCandidates(): List<SleepCandidate> {
        val jsonStr = preferences.getString(AutoSleepConfig.PENDING_JSON_KEY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<SleepCandidate>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val start = obj.getLong("start")
                val stop = obj.getLong("stop")
                val policyName = obj.getString("policy")
                val generatedAt = obj.getLong("generatedAt")
                val version = obj.optInt("detectorVersion", 1)
                list.add(
                    SleepCandidate(
                        start = start,
                        stop = stop,
                        policy = AutoSleepPolicyId.valueOf(policyName),
                        generatedAt = generatedAt,
                        detectorVersion = version
                    )
                )
            }
            list.sortedByDescending { it.stop }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Adds a candidate to the pending list if it is not already pending or rejected.
     *
     * Enforces MAX_PENDING capacity limit by sorting by stop timestamp descending and keeping
     * at most the top 7 candidates.
     *
     * @param candidate The detected sleep candidate to add.
     * @param now Current epoch millisecond timestamp (default: System.currentTimeMillis()).
     * @return True if candidate was added to pending store, false if ignored.
     */
    fun addPending(candidate: SleepCandidate, now: Long = System.currentTimeMillis()): Boolean {
        if (isPending(candidate.fingerprint) || isRejected(candidate.fingerprint, now)) {
            return false
        }
        val current = getPendingCandidates().toMutableList()
        current.add(candidate)
        val sortedAndCapped = current
            .distinctBy { it.fingerprint }
            .sortedByDescending { it.stop }
            .take(AutoSleepConfig.MAX_PENDING)

        savePending(sortedAndCapped)
        return true
    }

    /**
     * Removes the specified candidate from the pending list upon user acceptance or auto-save.
     *
     * @param candidate The candidate to remove.
     */
    fun markAccepted(candidate: SleepCandidate) {
        val current = getPendingCandidates().filterNot { it.fingerprint == candidate.fingerprint }
        savePending(current)
    }

    /**
     * Removes the candidate from pending store and records its fingerprint in the rejected store.
     *
     * Purges expired records older than 96h TTL and caps rejected records to MAX_REJECTED (30).
     *
     * @param candidate The candidate rejected by the user.
     * @param now Current epoch millisecond timestamp (default: System.currentTimeMillis()).
     */
    fun markRejected(candidate: SleepCandidate, now: Long = System.currentTimeMillis()) {
        markAccepted(candidate)
        val rejected = getRejectedRecords(now).toMutableList()
        rejected.removeAll { it.fingerprint == candidate.fingerprint }
        rejected.add(0, RejectedRecord(fingerprint = candidate.fingerprint, rejectedAt = now))
        val capped = rejected.take(AutoSleepConfig.MAX_REJECTED)
        saveRejected(capped)
    }

    /**
     * Checks if the given fingerprint is in the rejected list and within the 96h TTL window.
     *
     * @param fingerprint Candidate fingerprint string.
     * @param now Current epoch millisecond timestamp (default: System.currentTimeMillis()).
     * @return True if rejected within TTL, false otherwise.
     */
    fun isRejected(fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        val rejected = getRejectedRecords(now)
        return rejected.any { it.fingerprint == fingerprint }
    }

    /**
     * Checks if the given fingerprint is currently in the pending candidate list.
     *
     * @param fingerprint Candidate fingerprint string.
     * @return True if pending, false otherwise.
     */
    fun isPending(fingerprint: String): Boolean {
        return getPendingCandidates().any { it.fingerprint == fingerprint }
    }

    /**
     * Clears all pending candidates and rejected records from storage.
     */
    fun clear() {
        preferences.edit()
            .remove(AutoSleepConfig.PENDING_JSON_KEY)
            .remove(AutoSleepConfig.REJECTED_JSON_KEY)
            .apply()
    }

    /**
     * Internal helper to load and TTL-purge rejected records.
     */
    private fun getRejectedRecords(now: Long): List<RejectedRecord> {
        val jsonStr = preferences.getString(AutoSleepConfig.REJECTED_JSON_KEY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<RejectedRecord>()
            val cutoff = now - AutoSleepConfig.REJECTED_TTL_HOURS * 3600 * 1000L
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val fingerprint = obj.getString("fingerprint")
                val rejectedAt = obj.getLong("rejectedAt")
                if (rejectedAt >= cutoff) {
                    list.add(RejectedRecord(fingerprint = fingerprint, rejectedAt = rejectedAt))
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Internal helper to serialize pending candidates list to SharedPreferences.
     */
    private fun savePending(candidates: List<SleepCandidate>) {
        val array = JSONArray()
        for (c in candidates) {
            val obj = JSONObject().apply {
                put("start", c.start)
                put("stop", c.stop)
                put("policy", c.policy.name)
                put("generatedAt", c.generatedAt)
                put("detectorVersion", c.detectorVersion)
            }
            array.put(obj)
        }
        preferences.edit().putString(AutoSleepConfig.PENDING_JSON_KEY, array.toString()).apply()
    }

    /**
     * Internal helper to serialize rejected records list to SharedPreferences.
     */
    private fun saveRejected(records: List<RejectedRecord>) {
        val array = JSONArray()
        for (r in records) {
            val obj = JSONObject().apply {
                put("fingerprint", r.fingerprint)
                put("rejectedAt", r.rejectedAt)
            }
            array.put(obj)
        }
        preferences.edit().putString(AutoSleepConfig.REJECTED_JSON_KEY, array.toString()).apply()
    }
}
