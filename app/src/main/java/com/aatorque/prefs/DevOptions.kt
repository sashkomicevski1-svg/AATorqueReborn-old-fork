package com.aatorque.prefs

import android.content.Context
import com.aatorque.stats.BuildConfig

/**
 * Developer options.
 *
 * They live in their own SharedPreferences on purpose: they are not part of the dashboard
 * configuration, so they do not travel with an export, and the default can follow the build
 * type. Simulation defaults on in the debug packages and off in release; an explicit choice
 * by the user wins from then on.
 */
object DevOptions {
    const val FILE = "dev_options"
    const val SIMULATE_VALUES = "simulateValues"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun simulateValues(context: Context): Boolean =
        prefs(context).getBoolean(SIMULATE_VALUES, BuildConfig.SIMULATE_METRICS)

    fun setSimulateValues(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SIMULATE_VALUES, enabled).apply()
    }

    const val QUIT_TORQUE_ON_EXIT = "quitTorqueOnExit"
    const val REFRESH_INTERVAL_MS = "refreshIntervalMs"
    const val DIAGNOSTICS = "connectionDiagnostics"
    const val DEFAULT_REFRESH_MS = 150L

    /** Off by default: the user's own routine decides when Torque closes. */
    fun quitTorqueOnExit(context: Context): Boolean =
        prefs(context).getBoolean(QUIT_TORQUE_ON_EXIT, false)

    fun refreshIntervalMs(context: Context): Long =
        prefs(context).getString(REFRESH_INTERVAL_MS, null)?.toLongOrNull()
            ?.coerceIn(50L, 2_000L) ?: DEFAULT_REFRESH_MS

    fun diagnostics(context: Context): Boolean = prefs(context).getBoolean(DIAGNOSTICS, true)
}
