package com.aatorque.stats

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber

typealias PidInfo = List<Pair<String, List<String>>>

/**
 * Local service the phone-side settings screens bind to for Torque's PID list. It owns one
 * [TorqueService] connection, so it gets the same rebind handling as the car screen instead
 * of a second copy of the binding code.
 */
class TorqueServiceWrapper : Service() {
    private val binder = LocalBinder()
    private val connection = TorqueService()
    var torqueBindSucceeded = false
        private set
    var pids: PidInfo? = null

    val torqueService: ITorqueService?
        get() = connection.torqueService

    override fun onCreate() {
        super.onCreate()
        torqueBindSucceeded = connection.startTorque(this)
    }

    override fun onDestroy() {
        connection.onDestroy(this)
        super.onDestroy()
    }

    class ListPids(val ts: ITorqueService, val onComplete: (PidInfo) -> Unit) : Runnable {
        override fun run() {
            try {
                val all = ts.listAllPIDs().toList()
                val supported = try {
                    ts.listECUSupportedPIDs()?.toSet() ?: emptySet()
                } catch (e: Exception) {
                    emptySet()
                }
                val pids = selectPids(all, supported).toTypedArray()
                val pidAttributes = ts.getPIDInformation(pids).map { it.split(",") }

                onComplete(
                    pids.zip(pidAttributes).sortedBy { it.second[0] }
                )
            } catch (e: Exception) {
                // A dead binder here must not crash the settings screen.
                Timber.e(e, "Could not list Torque PIDs")
            }
        }
    }

    fun loadPidInformation(force: Boolean = false, onComplete: ((PidInfo) -> Unit)? = null) {
        if (pids != null && !force) {
            onComplete?.invoke(pids!!)
            return
        }
        // When Torque is not available, do not leave callers waiting forever: hand back
        // whatever was cached (or an empty list) so settings can be configured off-car.
        // Fresh data from a live Torque connection still wins when it arrives later.
        if (connection.torqueService == null) {
            onComplete?.invoke(pids ?: emptyList())
            // A failed first bind is retried by the connection, so ask about the latest one.
            if (!connection.lastBindOk) return
        }
        connection.addConnectCallback {
            val bg = ListPids(it) { newPids ->
                this.pids = newPids
                onComplete?.invoke(newPids)
            }
            Thread(bg).start()
        }
    }

    /**
     * Class used for the client Binder. Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods.
        fun getService(): TorqueServiceWrapper = this@TorqueServiceWrapper
    }

    fun isAvailable(): Boolean = connection.torqueService != null

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        fun runStartIntent(context: Context, conn: ServiceConnection): Boolean {
            Intent(context, TorqueServiceWrapper::class.java).also { intent ->
                return context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        }
    }
}
