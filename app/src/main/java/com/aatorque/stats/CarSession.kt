package com.aatorque.stats

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber

/**
 * One Android Auto session: from the car screen opening to it closing (engine off, phone
 * unplugged). It runs the DPF assistant, fault codes and the trip summary on the readings of
 * the car dashboard's refresh tick.
 *
 * Only the car dashboard feeds it; the phone-side preview does not. Slow Torque calls (the PID
 * list, raw fault code commands) and file work run on the session's own thread, never on the
 * refresh thread, so the gauges never wait for them.
 */
object CarSession {
    private const val ROLE_RETRY_MS = 60_000L
    private const val ROLE_ATTEMPTS = 3
    private const val KEY_DTC = "dtc"

    private var appContext: Context? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "car-session").apply { isDaemon = true }
    }

    @Volatile
    var running = false
        private set

    /** Identifies the session the car activity started, so a late end() of an old one is ignored. */
    private var token = 0

    @Volatile
    private var roles: Map<PidRole, String> = emptyMap()
    private var rolesTriedAt = 0L
    private var roleAttempts = 0
    private val roleBusy = AtomicBoolean(false)
    private val dtcBusy = AtomicBoolean(false)

    @Volatile
    private var dpf: DpfMonitor? = null

    @Volatile
    private var dtc: DtcMonitor? = null

    @Volatile
    private var trip: TripRecorder? = null

    @Volatile
    private var accel: AccelTimer? = null
    private var lastSpeedSample = Long.MIN_VALUE

    private val androidOutputs = object : DpfOutputs {
        override fun notice(key: String, notice: CarNotices.Notice?) {
            if (notice == null) CarNotices.remove(key) else CarNotices.post(key, notice)
        }

        override fun speak(text: String) = VoiceAlerts.speak(text)
        override fun showScreen(screen: Int) = CarScreenEvents.show(screen)
        override fun releaseScreen(screen: Int) = CarScreenEvents.release(screen)
    }

    fun tripHistory(context: Context) = JsonHistory(
        File(context.filesDir, "trips.json"),
        100,
        TripRecord::toJson,
        TripRecord::fromJson
    )

    fun dtcStore(context: Context) = JsonHistory(
        File(context.filesDir, "dtc_last.json"),
        1,
        DtcReading::toJson,
        DtcReading::fromJson
    )

    fun accelHistory(context: Context) = JsonHistory(
        File(context.filesDir, "accel.json"),
        100,
        AccelResult::toJson,
        AccelResult::fromJson
    )

    fun dpfHistory(context: Context) = JsonHistory(
        File(context.filesDir, "dpf_history.json"),
        200,
        RegenRecord::toJson,
        RegenRecord::fromJson
    )

    /** Start a session and return its token for [end]. A session still running is ended first. */
    @Synchronized
    fun start(context: Context): Int {
        if (running) endLocked()
        val app = context.applicationContext
        appContext = app
        roles = emptyMap()
        rolesTriedAt = 0L
        roleAttempts = 0
        dpf = if (!DpfSettings.enabled(app)) {
            null
        } else {
            DpfMonitor(
                dpfHistory(app),
                androidOutputs,
                texts = dpfTexts(app),
                screenDuringRegen = DpfSettings.screen(app),
                voice = DpfSettings.voice(app)
            )
        }
        dtc = if (!DpfSettings.dtcEnabled(app)) {
            null
        } else {
            DtcMonitor(
                dtcStore(app),
                notice = ::postDtcNotice,
                speak = { VoiceAlerts.speak(it) },
                texts = { codes -> dtcText(app, codes) }
            )
        }
        trip = if (DpfSettings.tripEnabled(app)) {
            TripRecorder(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        } else {
            null
        }
        accel = if (DpfSettings.accelEnabled(app)) {
            AccelTimer { kind, seconds -> onAccelResult(app, kind, seconds) }
        } else {
            null
        }
        lastSpeedSample = Long.MIN_VALUE
        CarScreenEvents.reset()
        running = true
        token++
        Timber.i("Session: started #$token")
        return token
    }

    /** End the session [sessionToken]; ignored when a newer session has started meanwhile. */
    @Synchronized
    fun end(sessionToken: Int) {
        if (!running || sessionToken != token) {
            Timber.i("Session: ignoring end of #$sessionToken (current #$token, running $running)")
            return
        }
        endLocked()
    }

    private fun endLocked() {
        running = false
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val endingDpf = dpf
        val endingTrip = trip
        dpf = null
        dtc = null
        trip = null
        accel = null
        CarNotices.clear()
        Timber.i("Session: ended #$token")
        // Files and the notification off the main thread.
        io.execute {
            endingDpf?.onSessionEnd(nowWall, nowElapsed)
            finishTrip(endingTrip, nowElapsed)
        }
    }

    private fun speedPid() = roles[PidRole.SPEED] ?: "0d,0"

    /** PIDs read on every tick (the acceleration timer needs every speed reading). */
    fun fastPids(): Collection<String> =
        if (running && accel != null) listOf(speedPid()) else emptyList()

    /** PIDs read for the session's features, besides the visible gauges. */
    fun monitorPids(): Collection<String> =
        if (running && (dpf != null || trip != null)) roles.values else emptyList()

    /** Called by the car dashboard's refresh thread after each successful read. */
    fun onTick(ts: ITorqueService?, byPid: Map<String, TorqueData>, nowElapsed: Long) {
        if (!running) return
        if (ts != null && needsRoles(nowElapsed) && roleBusy.compareAndSet(false, true)) {
            rolesTriedAt = nowElapsed
            roleAttempts++
            io.execute {
                try {
                    resolveRoles(ts)
                } finally {
                    roleBusy.set(false)
                }
            }
        }
        val readings = HashMap<PidRole, Double>()
        for ((role, pid) in roles) {
            val data = byPid[pid] ?: continue
            if (data.hasData()) readings[role] = data.lastData
        }
        val nowWall = System.currentTimeMillis()
        accel?.let { timer ->
            val speed = byPid[speedPid()]
            if (speed != null && speed.hasData()) {
                // Torque's own read time places the reading best; our clock otherwise.
                val sample = if (speed.timesKnown && speed.torqueUpdateTime > 0) {
                    speed.torqueUpdateTime
                } else {
                    nowElapsed
                }
                if (sample != lastSpeedSample) {
                    lastSpeedSample = sample
                    timer.feed(speed.lastData, sample)
                }
            }
        }
        dpf?.update(readings, nowWall, nowElapsed)
        trip?.update(readings, nowElapsed)
        val codes = dtc
        if (ts != null && codes != null && codes.due(nowElapsed) &&
            dtcBusy.compareAndSet(false, true)
        ) {
            io.execute {
                try {
                    codes.readNow(ts, System.currentTimeMillis())
                } finally {
                    dtcBusy.set(false)
                }
            }
        }
        CarNotices.expire(nowElapsed)
    }

    /**
     * The PID list is fetched once per session. Without a regeneration status PID (a petrol
     * car, or names that do not match) it is tried again a minute later, three times at most.
     */
    private fun needsRoles(now: Long): Boolean = roles.keys.none { it == PidRole.REGEN_STATUS } &&
        roleAttempts < ROLE_ATTEMPTS &&
        (rolesTriedAt == 0L || now - rolesTriedAt > ROLE_RETRY_MS)

    private fun resolveRoles(ts: ITorqueService) {
        val context = appContext ?: return
        try {
            val ids = ts.listAllPIDs() ?: return
            val info = ts.getPIDInformation(ids) ?: return
            val named = ids.zip(info.toList()).map { (id, csv) -> id to csv.substringBefore(',') }
            roles = PidRoles.resolve(named, PidRoles.manual(context))
            Timber.i("Session: PID roles $roles (from ${ids.size} Torque PIDs)")
        } catch (e: Exception) {
            Timber.e(e, "Session: could not list Torque PIDs")
        }
    }

    /** Session PIDs made Torque fail the batch (a stale manual choice?); stop asking for them. */
    fun onSessionPidsRejected() {
        Timber.w("Session: Torque rejected the session PIDs $roles; dropping the custom ones")
        roles = roles.filterKeys { it == PidRole.SPEED || it == PidRole.COOLANT }
        roleAttempts = ROLE_ATTEMPTS
    }

    private fun onAccelResult(context: Context, kind: AccelTimer.Kind, seconds: Double) {
        val label = context.getString(
            if (kind == AccelTimer.Kind.ZERO_100) R.string.accel_0_100 else R.string.accel_60_100
        )
        val text = context.getString(R.string.accel_result, label, seconds)
        Timber.i("Accel: $text")
        CarNotices.post(
            "accel",
            CarNotices.Notice(
                text,
                CarNotices.Level.INFO,
                40,
                expiresAt = SystemClock.elapsedRealtime() + 15_000
            )
        )
        io.execute {
            val history = accelHistory(context)
            history.save(history.load() + AccelResult(kind, seconds, System.currentTimeMillis()))
        }
    }

    private fun postDtcNotice(n: CarNotices.Notice?) {
        if (n == null) CarNotices.remove(KEY_DTC) else CarNotices.post(KEY_DTC, n)
    }

    private fun finishTrip(recorder: TripRecorder?, nowElapsed: Long) {
        recorder ?: return
        val context = appContext ?: return
        try {
            val regens = dpfHistory(context).load().filter { it.startWall >= recorder.startWall }
            val codes = dtcStore(context).load().lastOrNull()?.codes?.map { it.code }.orEmpty()
            val record = recorder.finish(nowElapsed, regens, codes)
            val history = tripHistory(context)
            history.save(history.load() + record)
            Timber.i("Trip: $record")
            TripNotifier.post(context, record)
        } catch (e: Exception) {
            Timber.e(e, "Trip: could not finish")
        }
    }

    private fun dtcText(context: Context, codes: List<Dtc>): String {
        val one = codes.singleOrNull()
        return if (one != null && one.description.isNotEmpty()) {
            context.getString(R.string.dtc_one, one.code, one.description)
        } else {
            context.getString(R.string.dtc_many, codes.joinToString { it.code })
        }
    }

    private fun dpfTexts(context: Context) = DpfTexts(
        active = { elapsed, km, detail ->
            context.getString(R.string.dpf_active, elapsed, km, detail)
        },
        started = context.getString(R.string.dpf_started),
        finished = context.getString(R.string.dpf_finished),
        paused = context.getString(R.string.dpf_paused),
        interrupted = context.getString(R.string.dpf_interrupted)
    )
}

/** The car assistant's settings (Settings → Car assistant). */
object DpfSettings {
    const val ENABLED = "dpfEnabled"
    const val DTC_ENABLED = "dtcEnabled"
    const val TRIP_ENABLED = "tripEnabled"
    const val ACCEL_ENABLED = "accelEnabled"
    const val VOICE = "dpfVoice"
    const val SCREEN = "dpfScreen"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PidRoles.PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, true)

    fun dtcEnabled(context: Context) = prefs(context).getBoolean(DTC_ENABLED, true)

    fun tripEnabled(context: Context) = prefs(context).getBoolean(TRIP_ENABLED, true)

    fun accelEnabled(context: Context) = prefs(context).getBoolean(ACCEL_ENABLED, true)

    fun voice(context: Context) = prefs(context).getBoolean(VOICE, true)

    /** Dashboard screen to show during a regeneration, or -1 for none. */
    fun screen(context: Context) = prefs(context).getString(SCREEN, "-1")?.toIntOrNull() ?: -1
}
