package com.aatorque.stats

import android.icu.math.BigDecimal
import android.icu.text.NumberFormat
import androidx.lifecycle.MutableLiveData
import com.aatorque.datastore.Coloring
import com.aatorque.datastore.Display
import com.aatorque.datastore.Operation
import com.ezylang.evalex.BaseException
import com.ezylang.evalex.Expression
import com.ezylang.evalex.data.EvaluationValue
import timber.log.Timber

class TorqueData(var display: Display) {
    companion object {
        const val PREFIX = "torque_"
        const val MIN_STALE_MS = 2_000L
        val drawableRegex = Regex("res/drawable/(?<name>.+)\\.[a-z]+")
    }

    // NumberFormat is not thread-safe. The values are formatted on the refresh thread while
    // new gauges are built on the main thread, so every gauge owns its formatters.
    private val twoPlaces: NumberFormat = NumberFormat.getNumberInstance()
    private val intPlaces: NumberFormat = NumberFormat.getIntegerInstance()
    private val onePlace: NumberFormat = NumberFormat.getNumberInstance()
    private val twoPlace: NumberFormat = NumberFormat.getNumberInstance()

    init {
        twoPlaces.maximumFractionDigits = 2
        twoPlaces.minimumFractionDigits = 0
        twoPlaces.isGroupingUsed = true
        intPlaces.isGroupingUsed = true
        twoPlaces.roundingMode = BigDecimal.ROUND_HALF_UP
        onePlace.minimumFractionDigits = 1
        onePlace.maximumFractionDigits = 1
        onePlace.isGroupingUsed = true
        onePlace.roundingMode = BigDecimal.ROUND_HALF_UP
        twoPlace.minimumFractionDigits = 2
        twoPlace.maximumFractionDigits = 2
        twoPlace.isGroupingUsed = true
        twoPlace.roundingMode = BigDecimal.ROUND_HALF_UP
    }

    var pid: String? = null
        get() {
            return if (display.disabled) null else field
        }
    var minValue: Double = Double.POSITIVE_INFINITY
    var maxValue: Double = Double.NEGATIVE_INFINITY
    private var expression: Expression? = null
    var lastDataStr: String = "-"
    var hasReceivedNonZero = false

    /** Torque reported a read time for this PID, so its value is real data, even 0. */
    var hasValidRead = false

    /** Torque's own timestamp (ms) of its last read of this PID; 0 = never read. */
    var torqueUpdateTime = 0L

    /** Our elapsedRealtime when [torqueUpdateTime] last changed. */
    var lastFreshElapsed = 0L

    /** Smoothed time between Torque's reads of this PID, from its own timestamps. */
    var observedPeriodMs = 0L

    /** Dashboard screen this display belongs to, for "switch to this screen"; -1 = unknown. */
    var screenIndex = -1

    /** A value was published at least once. */
    var hasAnyRead = false

    /** Torque reports read times for this PID, so [hasValidRead] is meaningful. */
    var timesKnown = false

    /**
     * The value is real data: Torque confirmed a read, or (when it reports no read times)
     * a value was published.
     */
    fun hasData(): Boolean = if (timesKnown) hasValidRead else hasAnyRead

    /** Read only for its sound alarms, from a screen that is not shown; never drawn. */
    var isWatcher = false

    /** True when Torque stopped refreshing this PID; the view greys the value out. */
    var isStale = false
    var currentAlarm = MutableLiveData<Coloring?>()

    /**
     * Every reading this gauge received, whether or not a chart was on screen. The chart
     * seeds itself from this, so a swipe down shows the last minutes instead of starting
     * from the moment it opened.
     */
    val history = PidHistory()

    var notifyUpdate: ((TorqueData) -> Unit)? = null
        set(value) {
            field = value
            value?.let { it(this) }
        }

    var lastData: Double = 0.0
        set(value) {
            val converted = convertValue(value)
            field = converted.first
            lastDataStr = converted.second
            history.add(System.currentTimeMillis(), field)
            if (field > maxValue) {
                maxValue = field
            }
            if (field < minValue) {
                minValue = field
            }
            updateAlarm(field)
        }

    /**
     * Track Torque's read timestamp for this PID. Stale means the timestamp has not moved for
     * three observed periods, and at least two seconds. Elapsed time is measured on our clock
     * only, so Torque's clock base never has to match ours.
     */
    fun trackUpdateTime(torqueTime: Long, nowElapsed: Long) {
        if (torqueTime <= 0L) {
            // Torque has no read for this PID (yet, or again after it restarted). Forget the
            // old session: a 0 now is not data, and the value still on screen is old, so it
            // is shown as stale if there ever was one.
            hasValidRead = false
            torqueUpdateTime = 0L
            observedPeriodMs = 0L
            isStale = lastFreshElapsed != 0L
            return
        }
        hasValidRead = true
        if (torqueTime != torqueUpdateTime) {
            if (torqueUpdateTime > 0L) {
                val delta = torqueTime - torqueUpdateTime
                if (delta in 1..60_000) {
                    observedPeriodMs =
                        if (observedPeriodMs == 0L) delta else (observedPeriodMs * 3 + delta) / 4
                }
            }
            torqueUpdateTime = torqueTime
            lastFreshElapsed = nowElapsed
        }
        isStale = nowElapsed - lastFreshElapsed > staleAfterMs()
    }

    fun staleAfterMs(): Long = maxOf(3 * observedPeriodMs, MIN_STALE_MS)

    private fun updateAlarm(field: Double) {
        // An alarm needs real data. Torque reports 0 before the ECU answers, so a 0 counts
        // only once Torque confirms it read the PID; a non-zero value always counts.
        if (!hasValidRead && !hasReceivedNonZero) {
            return
        }
        val newValue = display.alarmsList.firstOrNull {
            when (it.operation) {
                Operation.GT -> field > it.value
                Operation.GTE -> field >= it.value
                Operation.EQ -> field == it.value
                Operation.LT -> field < it.value
                Operation.LTE -> field <= it.value
                else -> false
            }
        }
        if (currentAlarm.value?.color != newValue?.color) {
            currentAlarm.postValue(newValue)
        }
        updateAlarmSound(newValue)
    }

    /** The rule that matched at the last evaluation, for start and end sounds. */
    private var activeRule: Coloring? = null
    private var alarmEvaluated = false

    /**
     * Sound on a change of the matching rule. A start sound also plays on the first reading
     * (a DPF regeneration already running when the car starts is worth knowing). An end sound
     * needs a rule that was seen matching before, so it never plays at start-up.
     */
    private fun updateAlarmSound(newRule: Coloring?) {
        val previous = activeRule
        if (newRule != previous) {
            if (alarmEvaluated && previous != null) {
                announce(previous, AlarmSound.Kind.END, previous.speakOnEnd, previous.beepOnEnd)
                if (previous.showScreen && screenIndex >= 0) CarScreenEvents.release(screenIndex)
            }
            if (newRule != null) {
                announce(newRule, AlarmSound.Kind.START, newRule.speakOnStart, newRule.beepOnStart)
                if (newRule.showScreen && screenIndex >= 0) CarScreenEvents.show(screenIndex)
            }
            activeRule = newRule
        }
        alarmEvaluated = true
    }

    /** Speak the rule's text if it has one, otherwise beep if asked; at most once per 10 s. */
    private fun announce(rule: Coloring, kind: AlarmSound.Kind, text: String, beep: Boolean) {
        if (text.isBlank() && !beep) return
        val key = soundKey(rule)
        if (!AlarmSound.allow(key, kind)) return
        if (text.isNotBlank()) VoiceAlerts.speak(text) else AlarmSound.playNow(key, kind)
    }

    private fun soundKey(rule: Coloring) = "${display.pid}|${rule.operation}${rule.value}"

    init {
        val value = display.pid
        if (value.startsWith(PREFIX)) {
            pid = value.substring(PREFIX.length)
        }
    }

    private fun convertValue(value: Double): Pair<Double, String> {
        val digits = resolvedDecimalPlaces(display)
        val numberFormatter = when (digits) {
            null -> twoPlaces
            0 -> intPlaces
            1 -> onePlace
            2 -> twoPlace
            else -> twoPlaces
        }
        if (!display.enableScript || display.customScript == "") {
            return Pair(
                value,
                try {
                    numberFormatter.format(value)
                } catch (ex: IllegalArgumentException) {
                    Timber.e("Exception formatting unconverted value $value", ex)
                    value.toString()
                }
            )
        }
        if (expression == null) {
            val strExp = display.customScript.replace("[x×]".toRegex(), "*")
            Timber.i("Attempting to make expression: $strExp")
            expression = Expression(strExp)
        }
        return try {
            val result = expression!!.with("a", value).evaluate()
            val asString = if (result.dataType == EvaluationValue.DataType.STRING) {
                result.stringValue
            } else {
                numberFormatter.format(result.numberValue)
            }
            Pair(result.numberValue.toDouble(), asString)
        } catch (ex: Exception) {
            when (ex) {
                is BaseException, is NoSuchElementException, is NumberFormatException -> {
                    Timber.e("Unable to parse", ex)
                    ex.printStackTrace()
                    Pair(0.0, "Error")
                }

                else -> throw ex
            }
        }
    }

    fun getDrawableName(): String? {
        val match = drawableRegex.matchEntire(display.icon)
        if (match != null) {
            return match.groups["name"]!!.value
        }
        return display.icon
    }

    fun sendNotifyUpdate() {
        // A sound watcher has no view by design.
        if (isWatcher) return
        if (notifyUpdate == null) {
            Timber.e("Cannot update, notifyUpdate is null")
        } else {
            notifyUpdate?.let {
                it(this)
            }
        }
    }

    fun stopRefreshing(isDestroying: Boolean = false) {
        if (isDestroying) {
            notifyUpdate = null
        }
    }
}

/** Opacity of a value Torque stopped refreshing. */
const val STALE_ALPHA = 0.4f
