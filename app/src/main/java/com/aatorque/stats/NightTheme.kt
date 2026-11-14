package com.aatorque.stats

import android.content.res.Configuration
import com.aatorque.datastore.UserPreference

/**
 * Picks the theme for day or night. With a night theme set, it is used while the car reports
 * night mode (headlights, or its light sensor, as Android Auto passes it on), or between
 * 19:00 and 07:00 when the clock is chosen instead.
 */
object NightTheme {
    const val NIGHT_FROM_HOUR = 19
    const val NIGHT_UNTIL_HOUR = 7

    fun effective(prefs: UserPreference, isNight: Boolean): String =
        if (isNight && prefs.nightTheme.isNotBlank()) prefs.nightTheme else prefs.selectedTheme

    fun isNightByClock(hour: Int): Boolean = hour >= NIGHT_FROM_HOUR || hour < NIGHT_UNTIL_HOUR

    fun isNightConfig(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isNight(prefs: UserPreference, carNight: Boolean, hour: Int): Boolean =
        if (prefs.nightBySchedule) isNightByClock(hour) else carNight
}
