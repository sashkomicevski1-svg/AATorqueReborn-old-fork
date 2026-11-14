package com.aatorque.stats

import org.json.JSONObject

/** One Android Auto session's drive. */
data class TripRecord(
    val startWall: Long,
    val durationMs: Long,
    val distanceKm: Double,
    val movingMs: Long,
    val maxSpeed: Double?,
    val maxCoolant: Double?,
    val regenerations: Int,
    val regenCompleted: Int,
    val faultCodes: List<String>
) {
    /** Average over the time the car moved, as a trip computer shows it. */
    val averageSpeed: Double?
        get() = if (movingMs > 60_000) distanceKm / (movingMs / 3_600_000.0) else null

    fun toJson(): JSONObject = JSONObject().apply {
        put("startWall", startWall)
        put("durationMs", durationMs)
        put("distanceKm", distanceKm)
        put("movingMs", movingMs)
        maxSpeed?.let { put("maxSpeed", it) }
        maxCoolant?.let { put("maxCoolant", it) }
        put("regenerations", regenerations)
        put("regenCompleted", regenCompleted)
        put("faultCodes", faultCodes.joinToString(","))
    }

    companion object {
        fun fromJson(o: JSONObject) = TripRecord(
            startWall = o.getLong("startWall"),
            durationMs = o.optLong("durationMs"),
            distanceKm = o.optDouble("distanceKm", 0.0),
            movingMs = o.optLong("movingMs"),
            maxSpeed = if (o.has("maxSpeed")) o.optDouble("maxSpeed") else null,
            maxCoolant = if (o.has("maxCoolant")) o.optDouble("maxCoolant") else null,
            regenerations = o.optInt("regenerations"),
            regenCompleted = o.optInt("regenCompleted"),
            faultCodes = o.optString("faultCodes").split(',').filter { it.isNotBlank() }
        )
    }
}

/**
 * Collects a trip from the session's readings: distance from the total distance PID when the
 * car has one, from speed over time otherwise; time moving; highest speed and coolant.
 */
class TripRecorder(val startWall: Long, private val startElapsed: Long) {
    private var startTotalKm: Double? = null
    private var lastTotalKm: Double? = null
    private var integratedKm = 0.0
    private var movingMs = 0L
    private var lastSpeedElapsed: Long? = null
    private var lastSpeed: Double? = null
    private var maxSpeed: Double? = null
    private var maxCoolant: Double? = null

    companion object {
        const val MOVING_KMH = 3.0
        const val MAX_GAP_MS = 5_000L
    }

    @Synchronized
    fun update(readings: Map<PidRole, Double>, nowElapsed: Long) {
        readings[PidRole.TOTAL_DISTANCE]?.let { km ->
            if (startTotalKm == null) startTotalKm = km
            lastTotalKm = km
        }
        readings[PidRole.COOLANT]?.let { c -> maxCoolant = maxOf(maxCoolant ?: c, c) }
        val speed = readings[PidRole.SPEED] ?: return
        maxSpeed = maxOf(maxSpeed ?: speed, speed)
        val previous = lastSpeed
        val previousAt = lastSpeedElapsed
        if (previous != null && previousAt != null) {
            val dt = (nowElapsed - previousAt).coerceIn(0L, MAX_GAP_MS)
            // Trapezoid between two readings.
            integratedKm += (previous + speed) / 2.0 * dt / 3_600_000.0
            if (speed >= MOVING_KMH || previous >= MOVING_KMH) movingMs += dt
        }
        lastSpeed = speed
        lastSpeedElapsed = nowElapsed
    }

    @Synchronized
    fun finish(nowElapsed: Long, regens: List<RegenRecord>, codes: List<String>): TripRecord {
        val start = startTotalKm
        val end = lastTotalKm
        val distance = if (start != null && end != null &&
            end >= start
        ) {
            end - start
        } else {
            integratedKm
        }
        return TripRecord(
            startWall = startWall,
            durationMs = nowElapsed - startElapsed,
            distanceKm = distance,
            movingMs = movingMs,
            maxSpeed = maxSpeed,
            maxCoolant = maxCoolant,
            regenerations = regens.size,
            regenCompleted = regens.count { it.outcome == RegenRecord.Outcome.COMPLETED },
            faultCodes = codes
        )
    }
}
