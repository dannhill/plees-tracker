/*
 * Copyright 2026 Plees AutoSleep Authors
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker.autosleep

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Utility helper for evaluating Android system compatibility, Usage Access permission status,
 * and navigating defensively to system Settings.
 */
object UsageAccess {

    private const val TAG = "UsageAccess"

    /**
     * Checks if the device running Android version supports KEYGUARD_HIDDEN events (API 28+ / Android P).
     * In pure JVM unit tests, Build.VERSION.SDK_INT defaults to 0.
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * Checks if PACKAGE_USAGE_STATS (Usage Access) special permission has been granted by the user.
     *
     * @param context Application or Activity context.
     * @return true if permission is granted, false if denied or unsupported.
     */
    @Suppress("DEPRECATION")
    fun hasAccess(context: Context): Boolean {
        if (!isSupported()) {
            return false
        }
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Creates an Intent to navigate the user to the Android Usage Access settings screen.
     */
    fun createSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    /**
     * Opens the Usage Access settings screen from an Activity with fallback handling.
     *
     * @param activity The calling activity.
     * @return true if navigation succeeded, false if all attempts failed.
     */
    fun openSettings(activity: Activity): Boolean {
        return openSettings(activity as Context)
    }

    /**
     * Opens the Usage Access settings screen with multi-tier defensive try/catch fallbacks.
     *
     * Fallback order:
     * 1. Settings.ACTION_USAGE_ACCESS_SETTINGS (Usage access list)
     * 2. Settings.ACTION_APPLICATION_DETAILS_SETTINGS (App info screen)
     * 3. Settings.ACTION_SETTINGS (General system settings)
     *
     * @param context Context used to start the settings activity.
     * @return true if an activity was started, false otherwise.
     */
    fun openSettings(context: Context): Boolean {
        // 1. Primary target: Usage Access settings
        try {
            val usageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(usageIntent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_USAGE_ACCESS_SETTINGS unavailable; falling back to app details", e)
        }

        // 2. Secondary fallback: App details settings
        try {
            val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(appDetailsIntent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS unavailable; falling back to system settings", e)
        }

        // 3. Tertiary fallback: General system settings
        try {
            val systemSettingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(systemSettingsIntent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All settings navigation intents failed", e)
            return false
        }
    }
}
