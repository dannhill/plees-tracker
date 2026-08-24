/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.content.SharedPreferences

/**
 * Lightweight, in-memory SharedPreferences implementation for deterministic unit and E2E testing.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? {
        val value = data[key]
        return if (value is String) value else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
        val value = data[key]
        return if (value is Set<*>) value as Set<String> else defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        val value = data[key]
        return if (value is Int) value else defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        val value = data[key]
        return if (value is Long) value else defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        val value = data[key]
        return if (value is Float) value else defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val value = data[key]
        return if (value is Boolean) value else defValue
    }

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.remove(listener)
    }

    inner class FakeEditor : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private var clearFlag = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value != null) temp[key] = value else temp[key] = null
            }
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) {
                if (values != null) temp[key] = HashSet(values) else temp[key] = null
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFlag = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearFlag) {
                data.clear()
            }
            for ((k, v) in temp) {
                if (v == null) {
                    data.remove(k)
                } else {
                    data[k] = v
                }
                listeners.forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, k) }
            }
            temp.clear()
            clearFlag = false
        }
    }
}
