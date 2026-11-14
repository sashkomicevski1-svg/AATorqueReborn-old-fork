package com.aatorque.stats

import android.content.Context
import android.os.SystemClock
import java.io.File
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber

/**
 * One Android Auto session: from the car screen opening to it closing (engine off, phone
 * unplugged). It runs the DPF assistant, and later the fault code and trip features, on the
 * readings of the car dashboard's refresh tick.
 *
 * Only the car dashboard feeds it. The phone-side preview does not.
 */
object CarSession {
    private const val ROLE_RETRY_MS = 60_000L

    private var appContext: Context? = null

    @Volatile
    var running = false
        private set

    @Volatile
    private var roles: Map<PidRole, String> = emptyMap()
    private var rolesTriedAt = 0L
    private var dpf: DpfMonitor? = null

    private val androidOutputs = object : DpfOutputs {
        override fun notice(key: String, notice: CarNotices.Notice?) {
            if (notice == null) CarNotices.remove(key) else CarNotices.post(key, notice)
        }

        override fun speak(text: String) = VoiceAlerts.speak(text)
        override fun showScreen(screen: Int) = CarScreenEvents.show(screen)
        override fun releaseScreen(screen: Int) = CarScreenEvents.release(screen)
    }

    fun dpfHistory(context: Context) = JsonHistory(
        File(context.filesDir, "dpf_history.json"),
        200,
        RegenRecord::toJson,
        RegenRecord::fromJson
    )

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        roles = emptyMap()
        rolesTriedAt = 0L
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
        running = true
        Timber.i("Session: started")
    }

    @Synchronized
    fun end() {
        if (!running) return
        running = false
        dpf?.onSessionEnd(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        dpf = null
        CarNotices.clear()
        Timber.i("Session: ended")
    }

    /** PIDs read for the session's features, besides the visible gauges. */
    fun monitorPids(): Collection<String> = if (running &&
        dpf != null
    ) {
        roles.values
    } else {
        emptyList()
    }

    /** Called by the car dashboard's refresh thread after each successful read. */
    fun onTick(ts: ITorqueService?, byPid: Map<String, TorqueData>, nowElapsed: Long) {
        if (!running) return
        if (ts != null && needsRoles(nowElapsed)) resolveRoles(ts, nowElapsed)
        val readings = HashMap<PidRole, Double>()
        for ((role, pid) in roles) {
            val data = byPid[pid] ?: continue
            if (data.hasData()) readings[role] = data.lastData
        }
        synchronized(this) {
            dpf?.update(readings, System.currentTimeMillis(), nowElapsed)
        }
        CarNotices.expire(nowElapsed)
    }

    private fun needsRoles(now: Long): Boolean = roles.keys.none { it == PidRole.REGEN_STATUS } &&
        (rolesTriedAt == 0L || now - rolesTriedAt > ROLE_RETRY_MS)

    /**
     * Ask Torque for every PID and its name once, and pick the PIDs per role. A long list is
     * one call, done once per session (and once a minute while nothing is found).
     */
    private fun resolveRoles(ts: ITorqueService, now: Long) {
        rolesTriedAt = now
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

/** The DPF assistant's own settings (Settings → DPF assistant). */
object DpfSettings {
    const val ENABLED = "dpfEnabled"
    const val VOICE = "dpfVoice"
    const val SCREEN = "dpfScreen"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PidRoles.PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, true)

    fun voice(context: Context) = prefs(context).getBoolean(VOICE, true)

    /** Dashboard screen to show during a regeneration, or -1 for none. */
    fun screen(context: Context) = prefs(context).getString(SCREEN, "-1")?.toIntOrNull() ?: -1
}
