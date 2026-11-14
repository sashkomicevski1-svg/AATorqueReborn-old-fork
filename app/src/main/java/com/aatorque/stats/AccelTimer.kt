package com.aatorque.stats

import org.json.JSONObject

/** A finished acceleration run. */
data class AccelResult(val kind: AccelTimer.Kind, val seconds: Double, val wall: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("seconds", seconds)
        .put("wall", wall)

    companion object {
        fun fromJson(o: JSONObject) = AccelResult(
            AccelTimer.Kind.valueOf(o.getString("kind")),
            o.getDouble("seconds"),
            o.getLong("wall")
        )
    }
}

/**
 * Times 0-100 and 60-100 km/h from the speed PID, by itself: no button.
 *
 * - 0-100 starts when the car leaves a standstill and ends at 100 km/h.
 * - 60-100 (overtaking) starts when the speed passes 60 km/h while rising.
 *
 * The moment a threshold is crossed is interpolated between two readings. OBD speed is whole
 * km/h, read a few times a second, so a result is an estimate (a few tenths of a second).
 *
 * A run is dropped when:
 * - the car is back at a standstill;
 * - the speed falls by more than [DROP_KMH] from its highest;
 * - the speed has not risen for [STALL_MS];
 * - two readings are more than [MAX_GAP_MS] apart (the phone locked, the adapter busy);
 * - it takes longer than the kind allows.
 */
class AccelTimer(private val onResult: (Kind, Double) -> Unit) {
    enum class Kind(val from: Double, val to: Double, val maxMs: Long) {
        ZERO_100(0.0, 100.0, 40_000L),
        SIXTY_100(60.0, 100.0, 30_000L)
    }

    companion object {
        const val STANDSTILL_KMH = 0.5
        const val DROP_KMH = 3.0
        const val STALL_MS = 3_000L
        const val MAX_GAP_MS = 1_500L
    }

    private class Run(val kind: Kind, val startMs: Double, var peakAt: Long) {
        var peak = kind.from
    }

    private var prevSpeed: Double? = null
    private var prevTime = 0L
    private var standstill = false
    private val runs = ArrayList<Run>()

    /** Forget everything, for example when the clock of the readings changes. */
    fun reset() {
        prevSpeed = null
        runs.clear()
        standstill = false
    }

    /** One speed reading at [timeMs] (one monotonic millisecond clock for all readings). */
    fun feed(speed: Double, timeMs: Long) {
        val ps = prevSpeed
        val pt = prevTime
        prevSpeed = speed
        prevTime = timeMs
        if (ps == null || timeMs <= pt) {
            standstill = speed <= STANDSTILL_KMH
            return
        }
        // A gap between readings: nothing measured across it can be trusted.
        if (timeMs - pt > MAX_GAP_MS) {
            runs.clear()
            standstill = speed <= STANDSTILL_KMH
            return
        }

        val nowStill = speed <= STANDSTILL_KMH
        // Back at a standstill: a 0-100 run that crept and stopped starts over.
        if (nowStill) runs.removeAll { it.kind == Kind.ZERO_100 }

        // Start a 0-100 run when leaving a standstill.
        if (standstill && !nowStill) {
            runs += Run(Kind.ZERO_100, cross(ps, pt, speed, timeMs, STANDSTILL_KMH), timeMs)
        }
        standstill = nowStill

        // Start a 60-100 run when passing 60 on the way up.
        if (ps < Kind.SIXTY_100.from && speed >= Kind.SIXTY_100.from &&
            runs.none { it.kind == Kind.SIXTY_100 }
        ) {
            runs += Run(Kind.SIXTY_100, cross(ps, pt, speed, timeMs, Kind.SIXTY_100.from), timeMs)
        }

        val done = ArrayList<Run>()
        for (run in runs) {
            if (speed > run.peak) {
                run.peak = speed
                run.peakAt = timeMs
            }
            when {
                speed >= run.kind.to -> {
                    val seconds = (cross(ps, pt, speed, timeMs, run.kind.to) - run.startMs) / 1000.0
                    if (seconds > 0) onResult(run.kind, seconds)
                    done += run
                }

                speed < run.peak - DROP_KMH ||
                    timeMs - run.peakAt > STALL_MS ||
                    timeMs - run.startMs > run.kind.maxMs -> done += run
            }
        }
        runs.removeAll(done.toSet())
    }

    /** Time at which the speed crossed [level] between two readings, by linear interpolation. */
    private fun cross(s0: Double, t0: Long, s1: Double, t1: Long, level: Double): Double {
        if (s1 == s0) return t1.toDouble()
        val f = ((level - s0) / (s1 - s0)).coerceIn(0.0, 1.0)
        return t0 + f * (t1 - t0)
    }
}
