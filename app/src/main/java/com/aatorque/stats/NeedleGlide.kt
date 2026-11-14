package com.aatorque.stats

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.github.anastr.speedviewlib.Gauge

/**
 * Moves a needle linearly to each new reading over about one of the PID's own update periods,
 * so it keeps gliding between readings instead of jumping and stopping.
 *
 * SpeedView's own `speedTo` uses a decelerating curve. Restarted on every reading, that curve
 * gives a fast jump, a stop, and another jump, which shows as a stutter under hard
 * acceleration. A linear move that lasts as long as the gap between readings lands just as
 * the next reading arrives. The needle then trails the reading by about one period. The
 * centre text shows the exact current value (see TorqueSpeedometer.displayValue).
 */
class NeedleGlide(private val gauge: Gauge) {
    private var animator: ValueAnimator? = null
    private var lastTarget = Float.NaN

    fun glideTo(target: Float, durationMs: Long) {
        // Every refresh tick calls this, also when Torque has not read a new value yet.
        // Restarting towards the same target would reset the timing and cause the stutter
        // this class exists to remove.
        if (target == lastTarget) return
        lastTarget = target
        animator?.cancel()
        val from = gauge.currentSpeed
        if (durationMs <= 0L || from == target) {
            gauge.setSpeedAt(target)
            return
        }
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { gauge.setSpeedAt(it.animatedValue as Float) }
            start()
        }
    }

    /** Jump without animation, for example when the gauge is set up again. */
    fun reset(value: Float) {
        animator?.cancel()
        animator = null
        lastTarget = Float.NaN
        gauge.setSpeedAt(value)
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    companion object {
        const val MIN_GLIDE_MS = 100L
        const val MAX_GLIDE_MS = 800L

        /**
         * About one update period of the PID, 20% longer so that a little jitter in Torque's
         * timing retargets a moving needle instead of letting it stop. Before the period is
         * known, the refresh tick is used.
         */
        fun durationFor(observedPeriodMs: Long, refreshIntervalMs: Long): Long {
            val base = if (observedPeriodMs > 0L) observedPeriodMs * 6 / 5 else refreshIntervalMs
            return base.coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
        }
    }
}
