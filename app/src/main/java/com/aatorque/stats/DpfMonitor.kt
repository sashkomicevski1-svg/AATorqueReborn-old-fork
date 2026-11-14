package com.aatorque.stats

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** One DPF regeneration as seen by the app. */
data class RegenRecord(
    val startWall: Long,
    var endWall: Long = 0L,
    var durationMs: Long = 0L,
    var distanceKm: Double = 0.0,
    /** Distance since the previous regeneration when this one started (the interval). */
    val intervalKm: Double? = null,
    val sootStart: Double? = null,
    var sootEnd: Double? = null,
    var triggerMax: Double? = null,
    val countStart: Double? = null,
    var countEnd: Double? = null,
    var outcome: Outcome = Outcome.IN_PROGRESS
) {
    enum class Outcome {
        IN_PROGRESS,

        /** Soot went down (or the counter moved): the regeneration did its job. */
        COMPLETED,

        /** The ECU stopped it while driving, soot still high; it resumes later. */
        PAUSED,

        /** Android Auto ended (engine off) while it ran; checked at the next start. */
        OPEN_AT_SHUTDOWN,

        /** It was open at shutdown and soot was still high at the next start. */
        INTERRUPTED
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("startWall", startWall)
        put("endWall", endWall)
        put("durationMs", durationMs)
        put("distanceKm", distanceKm)
        intervalKm?.let { put("intervalKm", it) }
        sootStart?.let { put("sootStart", it) }
        sootEnd?.let { put("sootEnd", it) }
        triggerMax?.let { put("triggerMax", it) }
        countStart?.let { put("countStart", it) }
        countEnd?.let { put("countEnd", it) }
        put("outcome", outcome.name)
    }

    companion object {
        private fun JSONObject.optDoubleOrNull(key: String) = if (has(key)) optDouble(key) else null

        fun fromJson(o: JSONObject) = RegenRecord(
            startWall = o.getLong("startWall"),
            endWall = o.optLong("endWall"),
            durationMs = o.optLong("durationMs"),
            distanceKm = o.optDouble("distanceKm", 0.0),
            intervalKm = o.optDoubleOrNull("intervalKm"),
            sootStart = o.optDoubleOrNull("sootStart"),
            sootEnd = o.optDoubleOrNull("sootEnd"),
            triggerMax = o.optDoubleOrNull("triggerMax"),
            countStart = o.optDoubleOrNull("countStart"),
            countEnd = o.optDoubleOrNull("countEnd"),
            outcome = runCatching { Outcome.valueOf(o.getString("outcome")) }
                .getOrDefault(Outcome.COMPLETED)
        )
    }
}

/** A small JSON list in app storage. Keeps the newest [limit] entries. */
class JsonHistory<T>(
    private val file: File,
    private val limit: Int,
    private val toJson: (T) -> JSONObject,
    private val fromJson: (JSONObject) -> T
) {
    @Synchronized
    fun load(): MutableList<T> = try {
        if (!file.exists()) {
            mutableListOf()
        } else {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        Timber.e(e, "Could not read ${file.name}")
        mutableListOf()
    }

    @Synchronized
    fun save(items: List<T>) {
        try {
            val arr = JSONArray()
            items.takeLast(limit).forEach { arr.put(toJson(it)) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not write ${file.name}")
        }
    }
}

/** What the DPF monitor needs from the outside world; replaced in tests. */
interface DpfOutputs {
    fun notice(key: String, notice: CarNotices.Notice?)
    fun speak(text: String)
    fun showScreen(screen: Int)
    fun releaseScreen(screen: Int)
}

/** Texts shown and spoken by the DPF assistant. Built from string resources in the app. */
data class DpfTexts(
    val active: (elapsed: String, km: String, detail: String) -> String =
        { e, k, d -> "DPF regeneration · $e · $k km$d" },
    val started: String = "DPF regeneration started",
    val finished: String = "DPF regeneration finished",
    val paused: String = "DPF regeneration paused. It continues later.",
    val interrupted: String =
        "The last DPF regeneration was interrupted. Keep driving to let it finish."
)

/**
 * Follows DPF regenerations from the regeneration status PID and keeps a history.
 *
 * Regeneration is "on" while the status reads above 0.5 (Mazda's status PID reads 1 during a
 * regeneration). A finished regeneration counts as completed when the soot load fell below
 * 35% of where it started, or the regeneration counter moved. Whether Mazda moves the counter
 * at the start or at the end is not known, so soot is the main test. The raw numbers go to the
 * log for calibration.
 */
class DpfMonitor(
    private val history: JsonHistory<RegenRecord>,
    private val out: DpfOutputs,
    var texts: DpfTexts = DpfTexts(),
    /** Dashboard screen to show while a regeneration runs; -1 = none. */
    var screenDuringRegen: Int = -1,
    var voice: Boolean = true
) {
    private var checkPending = true
    private var statusReadings = 0
    private var activeStreak = 0
    private var inactiveStreak = 0
    private var firstActiveWall = 0L
    private var firstActiveElapsed = 0L
    private var lastSaved = 0L
    private var active = false
    private var open: RegenRecord? = null
    private var startElapsed = 0L
    private var startTotalKm: Double? = null
    private var integratedKm = 0.0
    private var lastSpeedElapsed: Long? = null

    companion object {
        const val KEY_ACTIVE = "dpf.active"
        const val KEY_WARNING = "dpf.warning"
        const val COMPLETED_SOOT_FRACTION = 0.35
        const val WARNING_SHOW_MS = 90_000L
        const val NOTICE_ACTIVE_PRIORITY = 50
        const val NOTICE_WARNING_PRIORITY = 80

        /** Status readings in a row before a start or an end counts (about 4 s). */
        const val CONFIRM_READINGS = 2

        /** Give up waiting for a soot reading for the start-up check after this many. */
        const val CHECK_GIVE_UP_READINGS = 5
        const val SAVE_EVERY_MS = 30_000L
    }

    val isActive: Boolean get() = active

    @Synchronized
    fun update(readings: Map<PidRole, Double>, nowWall: Long, nowElapsed: Long) {
        integrateSpeed(readings[PidRole.SPEED], nowElapsed)
        val status = readings[PidRole.REGEN_STATUS] ?: return
        statusReadings++
        if (checkPending) checkPreviousSession(readings, nowElapsed)
        // One odd reading must not start or end a regeneration: two in a row are needed.
        val nowActive = status > 0.5
        if (nowActive) {
            if (activeStreak == 0) {
                firstActiveWall = nowWall
                firstActiveElapsed = nowElapsed
            }
            activeStreak++
            inactiveStreak = 0
        } else {
            inactiveStreak++
            activeStreak = 0
        }
        if (!active && activeStreak >= CONFIRM_READINGS) {
            start(readings, firstActiveWall, firstActiveElapsed)
        }
        if (active && nowActive) progress(readings, nowWall, nowElapsed)
        if (active && inactiveStreak >= CONFIRM_READINGS) {
            finish(readings, nowWall, nowElapsed, shutdown = false)
        }
    }

    /** Android Auto is ending: an open regeneration is marked for a check at the next start. */
    @Synchronized
    fun onSessionEnd(nowWall: Long, nowElapsed: Long) {
        if (active) finish(emptyMap(), nowWall, nowElapsed, shutdown = true)
        out.notice(KEY_ACTIVE, null)
        checkPending = true
        statusReadings = 0
        activeStreak = 0
        inactiveStreak = 0
    }

    private fun integrateSpeed(speedKmh: Double?, nowElapsed: Long) {
        // Ticks without a speed reading (it is read every 2 s when not on screen) keep the
        // last reading time, so the next reading covers the whole gap (at most 5 s).
        speedKmh ?: return
        val previousAt = lastSpeedElapsed
        if (previousAt != null && active) {
            val dt = (nowElapsed - previousAt).coerceIn(0L, 5_000L)
            integratedKm += speedKmh * dt / 3_600_000.0
        }
        lastSpeedElapsed = nowElapsed
    }

    private fun distanceKm(readings: Map<PidRole, Double>): Double {
        val total = readings[PidRole.TOTAL_DISTANCE]
        val start = startTotalKm
        return if (total != null && start != null && total >= start) total - start else integratedKm
    }

    private fun start(readings: Map<PidRole, Double>, nowWall: Long, nowElapsed: Long) {
        active = true
        startElapsed = nowElapsed
        startTotalKm = readings[PidRole.TOTAL_DISTANCE]
        integratedKm = 0.0
        val record = RegenRecord(
            startWall = nowWall,
            intervalKm = readings[PidRole.DISTANCE_SINCE_REGEN],
            sootStart = readings[PidRole.SOOT],
            triggerMax = readings[PidRole.REGEN_TRIGGER],
            countStart = readings[PidRole.REGEN_COUNT]
        )
        open = record
        save(record)
        lastSaved = nowElapsed
        Timber.i("DPF: regeneration started $readings")
        if (voice) out.speak(texts.started)
        if (screenDuringRegen >= 0) out.showScreen(screenDuringRegen)
    }

    private fun progress(readings: Map<PidRole, Double>, nowWall: Long, nowElapsed: Long) {
        val record = open ?: return
        record.durationMs = nowElapsed - startElapsed
        record.endWall = nowWall
        // Saved now and then, so a regeneration survives the app being killed.
        if (nowElapsed - lastSaved >= SAVE_EVERY_MS) {
            save(record)
            lastSaved = nowElapsed
        }
        readings[PidRole.REGEN_TRIGGER]?.let { t ->
            record.triggerMax = maxOf(record.triggerMax ?: t, t)
        }
        val elapsed = nowElapsed - startElapsed
        val km = distanceKm(readings)
        val soot = readings[PidRole.SOOT]
        // Kept up to date, so a shutdown mid-regeneration (no last reading) still has them.
        record.distanceKm = km
        soot?.let { record.sootEnd = it }
        val limit = readings[PidRole.SOOT_LIMIT]
        val trigger = readings[PidRole.REGEN_TRIGGER]
        val detail = when {
            trigger != null -> " · %.0f%%".format(trigger)
            soot != null && limit != null && limit > 0 -> " · %.1f/%.0f g".format(soot, limit)
            soot != null -> " · %.1f g".format(soot)
            else -> ""
        }
        out.notice(
            KEY_ACTIVE,
            CarNotices.Notice(
                texts.active(formatElapsed(elapsed), "%.1f".format(km), detail),
                CarNotices.Level.ACTIVE,
                NOTICE_ACTIVE_PRIORITY
            )
        )
    }

    private fun finish(
        readings: Map<PidRole, Double>,
        nowWall: Long,
        nowElapsed: Long,
        shutdown: Boolean
    ) {
        val record = open ?: RegenRecord(startWall = nowWall)
        active = false
        open = null
        record.endWall = nowWall
        record.durationMs = nowElapsed - startElapsed
        if (readings.isNotEmpty()) record.distanceKm = distanceKm(readings)
        record.sootEnd = readings[PidRole.SOOT] ?: record.sootEnd
        record.countEnd = readings[PidRole.REGEN_COUNT]
        record.outcome = when {
            shutdown -> RegenRecord.Outcome.OPEN_AT_SHUTDOWN
            isCompleted(record) -> RegenRecord.Outcome.COMPLETED
            else -> RegenRecord.Outcome.PAUSED
        }
        Timber.i("DPF: regeneration ended ${record.outcome} $readings")
        out.notice(KEY_ACTIVE, null)
        if (!shutdown && voice) {
            out.speak(
                if (record.outcome ==
                    RegenRecord.Outcome.COMPLETED
                ) {
                    texts.finished
                } else {
                    texts.paused
                }
            )
        }
        if (screenDuringRegen >= 0) out.releaseScreen(screenDuringRegen)
        save(record)
    }

    /** Insert or replace [record] (by start time) in the history file. */
    private fun save(record: RegenRecord) {
        val all = history.load()
        val index = all.indexOfLast { it.startWall == record.startWall }
        if (index >= 0) all[index] = record.copy() else all.add(record.copy())
        history.save(all)
    }

    private fun isCompleted(r: RegenRecord): Boolean {
        val s0 = r.sootStart
        val s1 = r.sootEnd
        if (s0 != null && s1 != null && s0 > 0 && s1 <= s0 * COMPLETED_SOOT_FRACTION) return true
        val c0 = r.countStart
        val c1 = r.countEnd
        return c0 != null && c1 != null && c1 > c0 && (s0 == null || s1 == null)
    }

    /**
     * A regeneration was open when the last session ended. With the engine back on, a soot load
     * still above 35% of its start means it did not finish.
     */
    private fun checkPreviousSession(readings: Map<PidRole, Double>, nowElapsed: Long) {
        val all = history.load()
        // The regeneration of this session (saved as soon as it starts) is not "the last one".
        val current = open?.startWall
        val last = all.lastOrNull { it.startWall != current }
        // OPEN_AT_SHUTDOWN: Android Auto ended during it. IN_PROGRESS: the app was killed.
        if (last == null || (
                last.outcome != RegenRecord.Outcome.OPEN_AT_SHUTDOWN &&
                    last.outcome != RegenRecord.Outcome.IN_PROGRESS
                )
        ) {
            checkPending = false
            return
        }
        val soot = readings[PidRole.SOOT]
        if (soot == null) {
            // Soot is read with the other DPF PIDs but may arrive a reading later.
            if (statusReadings >= CHECK_GIVE_UP_READINGS) {
                Timber.w("DPF: no soot reading; cannot tell how the last regeneration ended")
                checkPending = false
            }
            return
        }
        checkPending = false
        val start = last.sootStart
        val done = start != null && start > 0 && soot <= start * COMPLETED_SOOT_FRACTION
        last.sootEnd = soot
        last.outcome = if (done) RegenRecord.Outcome.COMPLETED else RegenRecord.Outcome.INTERRUPTED
        history.save(all)
        Timber.i("DPF: previous regeneration resolved as ${last.outcome}, soot now $soot")
        if (last.outcome == RegenRecord.Outcome.INTERRUPTED) {
            out.notice(
                KEY_WARNING,
                CarNotices.Notice(
                    texts.interrupted,
                    CarNotices.Level.WARNING,
                    NOTICE_WARNING_PRIORITY,
                    expiresAt = nowElapsed + WARNING_SHOW_MS
                )
            )
            if (voice) out.speak(texts.interrupted)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
