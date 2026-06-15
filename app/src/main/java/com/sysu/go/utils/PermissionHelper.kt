package com.sysu.go.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helper that checks all three permissions required for reliable background tracking:
 * 1. Location (fine + coarse)
 * 2. Notification (POST_NOTIFICATIONS on Android 13+)
 * 3. Battery Optimization (must be disabled so Android doesn't kill the foreground service)
 *
 * Also tracks whether the driver has completed the permission setup screen at least once,
 * so we don't annoy them on every app open.
 */
object PermissionHelper {

    private const val PREFS_NAME = "permission_setup"
    private const val KEY_SETUP_COMPLETED = "setup_completed"

    // ─── SharedPrefs for tracking first-time setup ────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasCompletedSetup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_COMPLETED, false)

    fun markSetupCompleted(context: Context) {
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
    }

    // ─── Individual permission checks ──────────────────────────────────────

    /**
     * Returns true only when BOTH fine and coarse location are granted.
     * ACCESS_COARSE_LOCATION alone is NOT enough — the app needs GPS-level accuracy.
     */
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine && coarse
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission on Android 13+. Below 13 it's
     * granted automatically at install time, so we return true.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Auto-granted pre-Android 13
        }
    }

    /**
     * Battery optimization must be IGNORED (whitelisted) so the foreground service
     * is not killed when the screen is off or the phone is idle.
     *
     * Use PowerManager.isIgnoringBatteryOptimizations() — available on API 23+.
     */
    @SuppressLint("BatteryLife")
    fun hasBatteryOptimizationDisabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Returns true only when ALL three required permissions are granted.
     */
    fun hasAllPermissions(context: Context): Boolean =
        hasLocationPermission(context) &&
        hasNotificationPermission(context) &&
        hasBatteryOptimizationDisabled(context)

    /**
     * Returns a list of permission names that are currently missing.
     * Used to show which specific items in the setup screen need attention.
     */
    fun getMissingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission(context)) missing.add("Location")
        if (!hasNotificationPermission(context)) missing.add("Notification")
        if (!hasBatteryOptimizationDisabled(context)) missing.add("Battery Optimization")
        return missing
    }

    /**
     * Whether we should show the permission setup screen.
     * Show it only when the driver hasn't completed setup OR when any
     * required permission is missing (covers re-prompting after denials).
     */
    fun needsSetup(context: Context): Boolean =
        !hasCompletedSetup(context) || !hasAllPermissions(context)
}