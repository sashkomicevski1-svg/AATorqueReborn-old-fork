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

    companion object {
        const val DEFAULT_REFRESH_INTERVAL = 150L
        const val DIAG_EVERY_MS = 5_000L

        /**
         * How long a needle takes to reach a new value. Deliberately shorter than the refresh
         * interval: the needle lands, holds, then moves again. An animation that lasts the
         * whole interval never finishes, so the needle creeps until the next sample.
         */
        @Volatile
        var needleAnimMs = needleFor(DEFAULT_REFRESH_INTERVAL)
            private set

        fun needleFor(intervalMs: Long): Long = (intervalMs * 4 / 5).coerceIn(80L, 200L)
    }

    /** Change the polling rate; a running timer restarts at the new rate. */
    fun updateInterval(intervalMs: Long, service: TorqueService) {
        if (intervalMs == refreshIntervalMs) return
        refreshIntervalMs = intervalMs
        needleAnimMs = needleFor(intervalMs)
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
        cache.getOrPut(screen) {
            HashMap()
        }[pos] = torqueData
        data[pos] = torqueData
        Timber.i("Setting query: $query for pos $pos on screen $screen")
        torqueData
    }

    suspend fun makeExecutors(service: TorqueService) {
        val targets = activeTargets()
        if (targets.isEmpty()) {
            stopRefreshTimer()
        } else if (refreshTimer == null) {
            // One task for the whole dashboard: every gauge is read and applied in the same
            // tick, so they share a phase and move on the same frame.
            Timber.i("Refreshing ${targets.size} items every ${refreshIntervalMs}ms")
            startTimer(service)
        }
        val status = if (targets.isNotEmpty()) conWatcher.value else ConnectStatus.SETUP_GAUGE
        conWatcher.emit(status)
        service.addConnectCallback {
            if (conWatcher.value == ConnectStatus.CONNECTING_TORQUE) {
                conWatcher.tryEmit(ConnectStatus.CONNECTING_ECU)
            }
        }
    }

    /**
     * The bind to Torque failed, so no connect callback will ever advance the state. Say so
     * instead of leaving the car on an unbounded "Connecting to Torque…" message.
     */
    fun reportTorqueMissing() {
        conWatcher.tryEmit(ConnectStatus.NO_TORQUE)
    }

    /** The bind failed, but the package may be present; do not claim it is missing. */
    fun reportTorqueUnavailable() {
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
        val targets = activeTargets()
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
                targets.forEach { doRefresh(service, it) }
                return@runIfConnected
            }
            // Torque's per-PID read time tells a real 0 from "never read" and drives the stale
            // marking. A failing call or a short answer falls back to the values alone.
            val times = try {
                ts.getPIDUpdateTime(pids.toTypedArray())?.takeIf { it.size == targets.size }
            } catch (e: Exception) {
                null
            }
            applyValues(targets, values, times, SystemClock.elapsedRealtime())
            handler.post {
                targets.forEach { it.sendNotifyUpdate() }
            }
            conWatcher.tryEmit(ConnectStatus.CONNECTED)
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
                torqueData.trackUpdateTime(time, nowElapsed)
                val neverRead = time <= 0L && !torqueData.hasValidRead
                val synthetic = torqueData.pid?.startsWith(SYNTHETIC_PID_PREFIX) == true
                if (neverRead && value == 0.0 && !synthetic) return@forEachIndexed
            }
            if (value != 0.0) {
                torqueData.hasReceivedNonZero = true
            }
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
            conWatcher.tryEmit(ConnectStatus.CONNECTED)
        }
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
    fun shutdown() {
        stopExecutors()
        executor?.shutdownNow()
        executor = null
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
        conWatcher.tryEmit(ConnectStatus.CONNECTED)
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
