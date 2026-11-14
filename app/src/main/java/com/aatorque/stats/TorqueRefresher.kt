package com.aatorque.stats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aatorque.datastore.Display
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runInterruptible
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber

enum class ConnectStatus {
    CONNECTING_TORQUE,
    CONNECTING_ECU,
    CONNECTED,
    SETUP_GAUGE,

    /** Torque Pro is missing, so no connection can ever be made. See issue #160. */
    NO_TORQUE,

    /**
     * The bind failed while Torque Pro may still be installed: no permission to bind, or a
     * service that refuses the caller. Say only that the connection failed. See issue #160.
     */
    TORQUE_UNAVAILABLE,

    /**
     * Torque Pro still answers but the ECU does not. The last values stay on screen, marked
     * as no longer live, instead of passing for a live reading. See issue #161.
     */
    STALE,

    /**
     * Torque Pro was connected and went away (killed, force-stopped, updated). The binding
     * rebuilds itself; until then the last values stay on screen marked as not live.
     */
    TORQUE_LOST
}

class TorqueRefresher {
    val data = HashMap<Int, TorqueData>()

    /** True while the developer option simulates values instead of reading Torque. */
    @Volatile
    var simulate = false

    private var executor: ScheduledThreadPoolExecutor? = null
    val handler = Handler(Looper.getMainLooper())
    private var conWatcher = MutableStateFlow(ConnectStatus.CONNECTING_TORQUE)
    val connectStatus = conWatcher.asSharedFlow()
    val cache = mutableMapOf<Int, HashMap<Int, TorqueData>>()
    private var refreshTimer: ScheduledFuture<*>? = null

    /**
     * How often the dashboard reads Torque. Torque returns the value its own adapter loop
     * already fetched, so this sets how fresh the screen is, not the OBD load. A tick costs
     * three cheap IPC calls, whatever the number of gauges.
     */
    @Volatile
    var refreshIntervalMs = DEFAULT_REFRESH_INTERVAL
        private set

    /** Log a connection summary every [DIAG_EVERY_MS]; see [logDiagnostics]. */
    @Volatile
    var diagnostics = false
    private var lastDiagElapsed = 0L

    /** The bind failure reported at start, until a read succeeds. */
    @Volatile
    private var torqueProblem: ConnectStatus? = null

    @Volatile
    private var pendingConnectCallback: ((org.prowl.torque.remote.ITorqueService) -> Unit)? = null

    companion object {
        const val DEFAULT_REFRESH_INTERVAL = 150L
        const val DIAG_EVERY_MS = 5_000L
        const val SESSION_EVERY_MS = 2_000L

        /** The refresh interval in use, for views that pace their animation by it. */
        @Volatile
        var activeIntervalMs = DEFAULT_REFRESH_INTERVAL
            private set
    }

    /** Change the polling rate; a running timer restarts at the new rate. */
    fun updateInterval(intervalMs: Long, service: TorqueService) {
        if (intervalMs == refreshIntervalMs) return
        refreshIntervalMs = intervalMs
        activeIntervalMs = intervalMs
        if (refreshTimer != null) {
            stopRefreshTimer()
            startTimer(service)
        }
    }

    private fun startTimer(service: TorqueService) {
        val pool = executor ?: ScheduledThreadPoolExecutor(1).also { executor = it }
        refreshTimer = pool.scheduleWithFixedDelay({
            try {
                refreshDashboard(service)
            } catch (e: Exception) {
                Timber.e(e, "Dashboard refresh failed")
            }
        }, 0L, refreshIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun populateQuery(pos: Int, screen: Int, query: Display): TorqueData = synchronized(data) {
        data[pos]?.stopRefreshing(true)
        val cacheItem = cache[screen]?.get(pos)
        val torqueData = if (cacheItem?.display?.equals(query) == true) {
            cacheItem
        } else {
            TorqueData(query)
        }
        torqueData.screenIndex = screen
        // Sounds, speech and screen switches come from the watchers only: they are read on
        // every screen, so their state never goes stale. Visible gauges only colour.
        torqueData.alertsEnabled = false
        cache.getOrPut(screen) {
            HashMap()
        }[pos] = torqueData
        data[pos] = torqueData
        Timber.i("Setting query: $query for pos $pos on screen $screen")
        torqueData
    }

    /**
     * PIDs with a sound alarm, from every dashboard screen. They are read in the same batch
     * as the visible gauges, so an alarm can sound (DPF regeneration started) while another
     * screen is shown. They are never drawn.
     */
    private val watchers = HashMap<Display, TorqueData>()

    fun setSoundWatchers(displays: List<Pair<Display, Int>>) = synchronized(data) {
        val keep = displays.distinctBy { it.first }
        watchers.keys.retainAll(keep.map { it.first }.toSet())
        keep.forEach { (display, screen) ->
            watchers.getOrPut(display) {
                TorqueData(display).apply {
                    isWatcher = true
                    screenIndex = screen
                    alertsEnabled = this@TorqueRefresher.alertsEnabled
                }
            }
        }
    }

    /** The car session reads its own PIDs (DPF, trip) through this refresher; null = none. */
    @Volatile
    var session: CarSession? = null

    /** TorqueData for the session's PIDs that are not already on screen, by Torque PID id. */
    private val sessionData = HashMap<String, TorqueData>()

    private var tickCount = 0L

    /**
     * Session PIDs change slowly (soot load, regeneration state), and every PID asked of
     * Torque can slow its adapter loop for the others. They are read every
     * [SESSION_EVERY_MS] instead of every tick.
     */
    private fun sessionDue(): Boolean {
        val every = (SESSION_EVERY_MS / refreshIntervalMs).coerceAtLeast(1L)
        return tickCount++ % every == 0L
    }

    /** False in the phone preview: nothing there may sound or switch screens. */
    @Volatile
    var alertsEnabled = true

    private var sessionIncluded = false
    private var fastSet: Set<TorqueData> = emptySet()
    private var sessionFailures = 0

    /** Visible gauges, the alert watchers, and (when due) the session's PIDs. */
    private fun pollTargets(includeSession: Boolean = true): List<TorqueData> = synchronized(data) {
        val visible = data.values.filter { it.pid != null }
        // Watchers are read even when the same PID is on screen: they carry the alert state.
        val watched = watchers.values.filter { it.pid != null }
        val known = HashSet<String?>().apply {
            visible.forEach { add(it.pid) }
            watched.forEach { add(it.pid) }
        }
        val fast = session?.fastPids().orEmpty().filter { it !in known }.toSet()
        val wanted = session?.monitorPids().orEmpty().filter { it !in known && it !in fast }.toSet()
        sessionData.keys.retainAll(wanted + fast)
        val fastData = fast.map(::sessionTorqueData)
        fastSet = fastData.toSet()
        if (!includeSession) return@synchronized visible + watched + fastData
        visible + watched + fastData + wanted.map(::sessionTorqueData)
    }

    private fun sessionTorqueData(pid: String): TorqueData = sessionData.getOrPut(pid) {
        TorqueData(Display.newBuilder().setPid(TorqueData.PREFIX + pid).build())
            .apply { isWatcher = true }
    }

    suspend fun makeExecutors(service: TorqueService) {
        val targets = activeTargets()
        if (pollTargets().isEmpty()) {
            stopRefreshTimer()
        } else if (refreshTimer == null) {
            // One task for the whole dashboard: every gauge is read and applied in the same
            // tick, so they share a phase and move on the same frame.
            Timber.i("Refreshing ${targets.size} items every ${refreshIntervalMs}ms")
            startTimer(service)
        }
        // "Set up gauges" only while there is nothing to show. It is also what an empty first
        // pass (before the dashboard loads) reports, so once gauges exist, go back to the bind
        // problem it covered, or to "connecting", instead of keeping it.
        val status = when {
            targets.isEmpty() -> ConnectStatus.SETUP_GAUGE

            conWatcher.value == ConnectStatus.SETUP_GAUGE ->
                torqueProblem
                    ?: if (service.torqueService != null) {
                        ConnectStatus.CONNECTING_ECU
                    } else {
                        ConnectStatus.CONNECTING_TORQUE
                    }

            else -> conWatcher.value
        }
        conWatcher.emit(status)
        // makeExecutors runs on every settings change. One pending callback is enough; more
        // would pile up on the shared connection until Torque answers.
        if (pendingConnectCallback == null) {
            val callback: (org.prowl.torque.remote.ITorqueService) -> Unit = {
                pendingConnectCallback = null
                if (conWatcher.value == ConnectStatus.CONNECTING_TORQUE) {
                    conWatcher.tryEmit(ConnectStatus.CONNECTING_ECU)
                }
            }
            pendingConnectCallback = callback
            service.addConnectCallback(callback)
        }
    }

    /**
     * The bind to Torque failed, so no connect callback will ever advance the state. Say so
     * instead of leaving the car on an unbounded "Connecting to Torque…" message.
     */
    fun reportTorqueMissing() {
        torqueProblem = ConnectStatus.NO_TORQUE
        conWatcher.tryEmit(ConnectStatus.NO_TORQUE)
    }

    /** The bind failed, but the package may be present; do not claim it is missing. */
    fun reportTorqueUnavailable() {
        torqueProblem = ConnectStatus.TORQUE_UNAVAILABLE
        conWatcher.tryEmit(ConnectStatus.TORQUE_UNAVAILABLE)
    }

    /**
     * Torque answered but the ECU did not. The dashboard keeps its last values, so it has to
     * say that they are not live. See issue #161.
     */
    fun reportEcuLost() {
        conWatcher.tryEmit(ConnectStatus.STALE)
    }

    /**
     * True when Torque still sees the ECU. A thrown call counts as present: the read that
     * follows reports its own failure, and a wrong "ECU lost" banner is worse than none.
     */
    private fun ecuPresent(service: TorqueService): Boolean {
        val ts = service.torqueService ?: return false
        return try {
            ts.isConnectedToECU()
        } catch (e: Exception) {
            Timber.e(e, "Torque failed the ECU state check")
            true
        }
    }

    /**
     * One dashboard read: every gauge PID in a single call, then one main-thread post so all
     * the needles move on the same frame. Public so the JVM test can drive it without a
     * scheduler.
     */
    fun refreshDashboard(service: TorqueService) {
        val includeSession = sessionDue()
        val targets = pollTargets(includeSession)
        // Only the slow session PIDs count: the fast ones (speed) are in every batch.
        sessionIncluded =
            includeSession && targets.any { it in sessionData.values && it !in fastSet }
        if (targets.isEmpty()) {
            return
        }
        if (simulate) {
            targets.forEach { doSimulatedRefresh(it) }
            return
        }
        val pids = targets.mapNotNull { it.pid }
        if (pids.size != targets.size) {
            targets.forEach { doRefresh(service, it) }
            return
        }
        if (service.torqueService == null) {
            reportTorqueLostIfWasConnected()
            return
        }
        service.runIfConnected { ts ->
            // Ask before the read: Torque answers from its own cache after the adapter is
            // gone, so a read alone cannot tell live data from the last frame. See issue #161.
            val ecu = ecuPresent(service)
            maybeLogDiagnostics(ts, targets, ecu)
            if (!ecu) {
                reportEcuLost()
                return@runIfConnected
            }
            val values = try {
                ts.getPIDValuesAsDouble(pids.toTypedArray())
            } catch (e: Exception) {
                Timber.e(e, "Torque failed the batched dashboard refresh")
                service.onCallFailed(e)
                if (service.torqueService == null) {
                    reportTorqueLostIfWasConnected()
                    return@runIfConnected
                }
                null
            }
            if (values == null || values.size != targets.size) {
                Timber.w("Torque returned ${values?.size ?: 0} values for ${pids.size} PIDs")
                if (sessionIncluded) {
                    // The car session's PIDs may be what Torque refuses (a stale manual
                    // choice). The next tick runs without them; after three such failures
                    // the session stops asking for its custom PIDs.
                    if (++sessionFailures >= 3) {
                        session?.onSessionPidsRejected()
                        sessionFailures = 0
                    }
                    return@runIfConnected
                }
                targets.forEach { doRefresh(service, it) }
                return@runIfConnected
            }
            if (sessionIncluded) sessionFailures = 0
            // Torque's per-PID read time tells a real 0 from "never read" and drives the stale
            // marking. A failing call or a short answer falls back to the values alone.
            val times = try {
                ts.getPIDUpdateTime(pids.toTypedArray())?.takeIf { it.size == targets.size }
            } catch (e: Exception) {
                null
            }
            val now = SystemClock.elapsedRealtime()
            applyValues(targets, values, times, now)
            handler.post {
                targets.forEach { it.sendNotifyUpdate() }
            }
            markConnected()
            session?.let { s ->
                try {
                    s.onTick(ts, targets.associateBy { it.pid.orEmpty() }, now)
                } catch (e: Exception) {
                    Timber.e(e, "Session tick failed")
                }
            }
        }
    }

    /**
     * Put a batched Torque result on its targets, in request order. Every successful read is
     * published, including 0.0 (issue #158).
     *
     * With [times] from `getPIDUpdateTime`, a PID that Torque says it never read (time 0) and
     * that reads 0 keeps showing "-" instead of a fake 0. Torque's synthetic `ff*` metrics are
     * exempt, since it is not known whether Torque stamps them. A timestamp also arms the
     * alarm gate and drives the stale marking.
     */
    fun applyValues(
        targets: List<TorqueData>,
        values: DoubleArray,
        times: LongArray? = null,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ) {
        targets.forEachIndexed { index, torqueData ->
            val value = values.getOrNull(index) ?: return@forEachIndexed
            val time = times?.getOrNull(index)
            if (time != null) {
                torqueData.timesKnown = true
                torqueData.trackUpdateTime(time, nowElapsed)
                val neverRead = time <= 0L && !torqueData.hasValidRead
                val synthetic = torqueData.pid?.startsWith(SYNTHETIC_PID_PREFIX) == true
                if (neverRead && value == 0.0 && !synthetic) return@forEachIndexed
            }
            if (value != 0.0) {
                torqueData.hasReceivedNonZero = true
            }
            torqueData.hasAnyRead = true
            torqueData.lastData = value
        }
    }

    private fun maybeLogDiagnostics(ts: ITorqueService, targets: List<TorqueData>, ecu: Boolean) {
        if (!diagnostics) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagElapsed < DIAG_EVERY_MS) return
        lastDiagElapsed = now
        logDiagnostics(ts, targets, ecu, now)
    }

    /**
     * One line every few seconds that answers "which side stopped": our tick (no line at
     * all), Torque's adapter loop (read speed 0, ages growing) or the ECU (ecu=false).
     */
    private fun logDiagnostics(
        ts: ITorqueService,
        targets: List<TorqueData>,
        ecu: Boolean,
        now: Long
    ) {
        fun safe(block: () -> Any?): String = try {
            block().toString()
        } catch (e: Exception) {
            "?"
        }
        val ages = targets.joinToString(" ") { td ->
            val age = if (td.lastFreshElapsed == 0L) "-" else "${now - td.lastFreshElapsed}"
            val stale = if (td.isStale) "/STALE" else ""
            "${td.pid}:age=$age/per=${td.observedPeriodMs}$stale"
        }
        Timber.i(
            "Diag: ecu=$ecu readSpeed=${safe { ts.getPIDReadSpeed() }} " +
                "configured=${safe { ts.getConfiguredSpeed() }} " +
                "fullAccess=${safe { ts.hasFullPermissions() }} " +
                "logged=${safe { ts.getNumberOfLoggedItems() }} " +
                "tick=${refreshIntervalMs}ms $ages"
        )
    }

    fun doRefresh(service: TorqueService, torqueData: TorqueData) {
        if (simulate) {
            doSimulatedRefresh(torqueData)
            return
        }
        service.runIfConnected { ts ->
            if (!ecuPresent(service)) {
                reportEcuLost()
                return@runIfConnected
            }
            val value = try {
                ts.getPIDValuesAsDouble(arrayOf(torqueData.pid!!))[0]
            } catch (e: ArrayIndexOutOfBoundsException) {
                Timber.e("Torque returned invalid data for ${torqueData.pid}")
                return@runIfConnected
            } catch (e: android.os.RemoteException) {
                Timber.e(e, "Torque failed the read for ${torqueData.pid}")
                service.onCallFailed(e)
                return@runIfConnected
            }
            torqueData.hasAnyRead = true
            torqueData.lastData = value
            Timber.d("Got valid $value from torque for ${torqueData.display.label}")
            // A real zero is data: speed and throttle are zero when the car stands still. The
            // flag stays for the alarm threshold check in TorqueData, which must ignore the
            // values Torque reports before the ECU answers at all.
            if (value != 0.0) {
                torqueData.hasReceivedNonZero = true
            }
            handler.post {
                torqueData.sendNotifyUpdate()
            }
            markConnected()
        }
    }

    private fun markConnected() {
        torqueProblem = null
        conWatcher.tryEmit(ConnectStatus.CONNECTED)
    }

    /**
     * Torque went away after it had answered. A state that says Torque was never there
     * (missing, refused) is kept, since the rebind cannot change that by itself.
     */
    private fun reportTorqueLostIfWasConnected() {
        when (conWatcher.value) {
            ConnectStatus.CONNECTED, ConnectStatus.STALE, ConnectStatus.CONNECTING_ECU ->
                conWatcher.tryEmit(ConnectStatus.TORQUE_LOST)

            else -> Unit
        }
    }

    private fun activeTargets(): List<TorqueData> =
        synchronized(data) { data.values.filter { it.pid != null } }

    fun stopExecutors() {
        Timber.i("Telling Torque refreshers to stop")
        stopRefreshTimer()
        synchronized(data) { data.values.toList() }.forEach { it.stopRefreshing() }
    }

    /** Stop polling for good and release the worker thread. */
    fun shutdown(service: TorqueService? = null) {
        stopExecutors()
        executor?.shutdownNow()
        executor = null
        // Drop every reference to this dashboard's views and to this refresher, so a
        // destroyed dashboard is not kept alive by the shared connection.
        synchronized(data) {
            (data.values + cache.values.flatMap { it.values }).forEach { it.stopRefreshing(true) }
            data.clear()
            cache.clear()
            watchers.clear()
            sessionData.clear()
        }
        pendingConnectCallback?.let { service?.removeConnectCallback(it) }
        pendingConnectCallback = null
    }

    private fun stopRefreshTimer() {
        refreshTimer?.cancel(false)
        refreshTimer = null
    }

    private fun doSimulatedRefresh(torqueData: TorqueData) {
        val value = SimulatedValue.at(torqueData.display, SystemClock.elapsedRealtime())
        torqueData.hasReceivedNonZero = true
        torqueData.lastData = value
        handler.post {
            torqueData.sendNotifyUpdate()
        }
        markConnected()
    }

    fun hasChanged(idx: Int, otherScreen: Display?): Boolean {
        if (!data.containsKey(idx)) return true
        return data[idx]?.display?.equals(otherScreen) != true
    }

    fun updateIfNeeded(pos: Int, screen: Int, query: Display): TorqueData =
        if (hasChanged(pos, query)) {
            populateQuery(pos, screen, query)
        } else {
            data[pos]!!
        }
}
