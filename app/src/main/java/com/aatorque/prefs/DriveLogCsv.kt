package com.aatorque.prefs

import com.aatorque.stats.AccelResult
import com.aatorque.stats.DtcReading
import com.aatorque.stats.RegenRecord
import com.aatorque.stats.TripRecord
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Drive log as CSV files in one ZIP: trips, DPF regenerations, acceleration runs and the
 * last fault codes. Times are ISO 8601 in UTC, numbers use a dot, fields are quoted when they
 * need to be, so any spreadsheet reads them.
 */
object DriveLogCsv {
    private fun time(ms: Long): String {
        if (ms <= 0) return ""
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(ms))
    }

    private fun num(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.3f", it) } ?: ""

    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun row(vararg values: String) = values.joinToString(",") { field(it) } + "\n"

    fun trips(trips: List<TripRecord>): String = buildString {
        append(
            row(
                "start_utc",
                "duration_min",
                "distance_km",
                "moving_min",
                "avg_speed_kmh",
                "max_speed_kmh",
                "max_coolant_c",
                "dpf_regens",
                "dpf_regens_completed",
                "fault_codes"
            )
        )
        trips.forEach { t ->
            append(
                row(
                    time(t.startWall),
                    num(t.durationMs / 60_000.0),
                    num(t.distanceKm),
                    num(t.movingMs / 60_000.0),
                    num(t.averageSpeed),
                    num(t.maxSpeed),
                    num(t.maxCoolant),
                    t.regenerations.toString(),
                    t.regenCompleted.toString(),
                    t.faultCodes.joinToString(" ")
                )
            )
        }
    }

    fun regens(regens: List<RegenRecord>): String = buildString {
        append(
            row(
                "start_utc",
                "end_utc",
                "duration_min",
                "distance_km",
                "interval_km",
                "soot_start",
                "soot_end",
                "trigger_max",
                "count_start",
                "count_end",
                "outcome"
            )
        )
        regens.forEach { r ->
            append(
                row(
                    time(r.startWall),
                    time(r.endWall),
                    num(r.durationMs / 60_000.0),
                    num(r.distanceKm),
                    num(r.intervalKm),
                    num(r.sootStart),
                    num(r.sootEnd),
                    num(r.triggerMax),
                    num(r.countStart),
                    num(r.countEnd),
                    r.outcome.name
                )
            )
        }
    }

    fun accel(runs: List<AccelResult>): String = buildString {
        append(row("time_utc", "run", "seconds"))
        runs.forEach { append(row(time(it.wall), it.kind.name, num(it.seconds))) }
    }

    fun codes(reading: DtcReading?): String = buildString {
        append(row("read_utc", "code", "pending", "description"))
        reading?.codes?.forEach {
            append(row(time(reading.wall), it.code, it.pending.toString(), it.description))
        }
    }

    /** Writes the four CSV files into one ZIP on [out]. */
    fun writeZip(
        out: OutputStream,
        trips: List<TripRecord>,
        regens: List<RegenRecord>,
        runs: List<AccelResult>,
        codes: DtcReading?
    ) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("trips.csv", trips(trips))
            put("dpf_regenerations.csv", regens(regens))
            put("acceleration.csv", accel(runs))
            put("fault_codes.csv", codes(codes))
        }
    }
}
