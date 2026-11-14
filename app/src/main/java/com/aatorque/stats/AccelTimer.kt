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
 * The moment a threshold is crossed is interpolated between two readings, using Torque's own
 * read times when it reports them. OBD speed is whole km/h, read a few times a second, so a
 * result is an estimate (a few tenths of a second). A run is dropped when the speed falls by
 * more than [DROP_KMH] from its highest, or when it takes too long.
 */
class AccelTimer(private val onResult: (Kind, Double) -> Unit) {
    enum class Kind(val from: Double, val to: Double, val maxMs: Long) {
        ZERO_100(0.0, 100.0, 40_000L),
        SIXTY_100(60.0, 100.0, 30_000L)
    }

    companion object {
        const val STANDSTILL_KMH = 0.5
        const val DROP_KMH = 3.0
    }

    private class Run(val kind: Kind, val startMs: Double) {
        var peak = kind.from
    }

    private var prevSpeed: Double? = null
    private var prevTime = 0L
    private var standstill = false
    private val runs = ArrayList<Run>()

    /** One speed reading at [timeMs] (any monotonic millisecond clock). */
    fun feed(speed: Double, timeMs: Long) {
        val ps = prevSpeed
        val pt = prevTime
        prevSpeed = speed
        prevTime = timeMs
        if (ps == null || timeMs <= pt) {
            standstill = speed <= STANDSTILL_KMH
            return
        }

        // Start a 0-100 run when leaving a standstill.
        if (standstill && speed > STANDSTILL_KMH && runs.none { it.kind == Kind.ZERO_100 }) {
            runs += Run(Kind.ZERO_100, cross(ps, pt, speed, timeMs, STANDSTILL_KMH))
        }
        standstill = speed <= STANDSTILL_KMH

        // Start a 60-100 run when passing 60 on the way up.
        if (ps < Kind.SIXTY_100.from && speed >= Kind.SIXTY_100.from &&
            runs.none { it.kind == Kind.SIXTY_100 }
        ) {
            runs += Run(Kind.SIXTY_100, cross(ps, pt, speed, timeMs, Kind.SIXTY_100.from))
        }

        val done = ArrayList<Run>()
        for (run in runs) {
            run.peak = maxOf(run.peak, speed)
            when {
                speed >= run.kind.to -> {
                    val end = cross(ps, pt, speed, timeMs, run.kind.to)
                    onResult(run.kind, (end - run.startMs) / 1000.0)
                    done += run
                }

                speed < run.peak - DROP_KMH || timeMs - run.startMs > run.kind.maxMs -> done += run
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
