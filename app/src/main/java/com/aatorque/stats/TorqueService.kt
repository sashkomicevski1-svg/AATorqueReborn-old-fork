package com.aatorque.stats

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import androidx.annotation.VisibleForTesting
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber

/**
 * The one binding to Torque Pro's plugin service.
 *
 * Torque can die underneath us (Samsung's process killer, a force stop, an update). Android
 * reports that in three ways and each needs its own answer:
 *
 * - [ServiceConnection.onServiceDisconnected]: the process died but the binding is kept, and
 *   Android reconnects by itself when Torque comes back. We only drop the stale interface.
 * - [ServiceConnection.onBindingDied]: the binding will never deliver again. We must unbind
 *   and bind anew, with a backoff so a missing Torque does not spin.
 * - [ServiceConnection.onNullBinding]: Torque refused to hand out an interface. Same answer.
 *
 * A [IBinder.DeathRecipient] drops the interface the moment the process dies, so the refresh
 * loop stops calling into a dead binder before the callbacks above arrive. A call that fails
 * with [RemoteException] reports the same through [onCallFailed].
 *
 * This object never asks Torque to quit; see [requestQuit] for the one opt-in place that does.
 */
class TorqueService(private val handler: Handler = Handler(Looper.getMainLooper())) {
    @Volatile
    var torqueService: ITorqueService? = null
        set(value) {
            field = value
            mutableState.value = value
        }

    private val mutableState = MutableStateFlow<ITorqueService?>(null)

    /** The live interface, or null while Torque is not bound. */
    val state: StateFlow<ITorqueService?> = mutableState.asStateFlow()

    private val onConnect = ArrayList<(ITorqueService) -> Unit>()
    private val conLock = ReentrantLock()

    /** True between a bindService call and its matching unbindService. */
    var hasBound = false
        private set

    /** Result of the last bindService call; false means Torque is missing or refused us. */
    @Volatile
    var lastBindOk = false
        private set

    /** True while the owner wants a binding; false after [onDestroy]. */
    private var wanted = false
    private var appContext: Context? = null
    private var backoffMs = MIN_BACKOFF_MS
    private var rebindPending = false
    private val rebindRunnable = Runnable { rebindNow() }

    /** How many times the binding was rebuilt, for the diagnostics log. */
    @Volatile
    var rebindCount = 0
        private set

    companion object {
        const val TORQUE_PACKAGE = "org.prowl.torque"
        const val TORQUE_SERVICE = "org.prowl.torque.remote.TorqueService"
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        /**
         * BIND_IMPORTANT asks Android to treat Torque as important as our foreground car
         * screen, which keeps its polling thread from being throttled first. BIND_ABOVE_CLIENT
         * is deliberately not used: it would let Android kill us before Torque.
         */
        const val BIND_FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT

        @VisibleForTesting
        fun nextBackoff(current: Long): Long = (current * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** Runs [func] once: now if Torque is bound, otherwise on the next connect. */
    fun addConnectCallback(func: (ITorqueService) -> Unit): TorqueService {
        val current = torqueService
        if (current == null) {
            conLock.withLock { onConnect.add(func) }
        } else {
            func(current)
        }
        return this
    }

    @VisibleForTesting
    fun pendingCallbackCount(): Int = conLock.withLock { onConnect.size }

    fun removeConnectCallback(func: (ITorqueService) -> Unit) {
        conLock.withLock { onConnect.remove(func) }
    }

    fun runIfConnected(func: (ITorqueService) -> Unit) {
        torqueService?.let(func)
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Timber.w("Torque process died")
        torqueService = null
    }

    private val torqueConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val svc = ITorqueService.Stub.asInterface(service)
            // Torque's test mode outlives our process. An earlier build enabled it when no
            // ECU was connected, and Torque then feeds random values that look like live
            // data. Simulation belongs to the app now, so always turn Torque's off.
            try {
                svc.setDebugTestMode(false)
            } catch (e: RemoteException) {
                Timber.w(e, "Could not disable Torque test mode")
            }
            try {
                service.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                // Already dead: onServiceDisconnected or onBindingDied follows.
                Timber.w(e, "Torque died while connecting")
                return
            }
            backoffMs = MIN_BACKOFF_MS
            torqueService = svc
            Timber.i("Torque connected (rebinds so far: $rebindCount)")
            val callbacks = conLock.withLock { onConnect.toList().also { onConnect.clear() } }
            callbacks.forEach { it(svc) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The binding stays registered and Android reconnects when Torque restarts.
            Timber.w("Torque disconnected; waiting for Android to reconnect")
            torqueService = null
        }

        override fun onBindingDied(name: ComponentName) {
            Timber.w("Torque binding died; rebinding")
            torqueService = null
            scheduleRebind()
        }

        override fun onNullBinding(name: ComponentName) {
            Timber.w("Torque returned a null binding; rebinding")
            torqueService = null
            scheduleRebind()
        }
    }

    /**
     * A call into Torque threw. A dead binder means the process is gone; drop the interface so
     * the dashboard says so, and let the connection callbacks bring it back.
     */
    fun onCallFailed(e: Exception) {
        if (e is android.os.DeadObjectException) {
            Timber.w("Torque call hit a dead binder")
            torqueService = null
            // Android normally follows with onServiceDisconnected and reconnects. If the
            // binding itself is gone, nothing follows, so rebind after the backoff anyway;
            // a rebind on a healthy binding is skipped in rebindNow(). This runs on the
            // refresh thread, and the rebind state belongs to the main thread.
            handler.post { scheduleRebind() }
        }
    }

    fun startTorque(context: Context): Boolean {
        appContext = context.applicationContext
        wanted = true
        return bind()
    }

    private fun bind(): Boolean {
        val context = appContext ?: return false
        val intent = Intent().setClassName(TORQUE_PACKAGE, TORQUE_SERVICE)
        val ok = try {
            context.bindService(intent, torqueConnection, BIND_FLAGS)
        } catch (e: SecurityException) {
            Timber.e(e, "Not allowed to bind to Torque")
            false
        }
        // bindService registers the connection even when it returns false, so it must be
        // unbound either way.
        hasBound = true
        lastBindOk = ok
        Timber.i(if (ok) "Bound to Torque service" else "Unable to bind to Torque plugin service")
        if (!ok) scheduleRebind()
        return ok
    }

    private fun unbind() {
        val context = appContext ?: return
        if (!hasBound) return
        hasBound = false
        try {
            context.unbindService(torqueConnection)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Torque service was not bound")
        }
    }

    private fun scheduleRebind() {
        if (!wanted || rebindPending) return
        rebindPending = true
        val delay = backoffMs
        backoffMs = nextBackoff(backoffMs)
        Timber.i("Rebinding to Torque in ${delay}ms")
        handler.postDelayed(rebindRunnable, delay)
    }

    private fun rebindNow() {
        rebindPending = false
        if (!wanted || torqueService != null) return
        rebindCount++
        unbind()
        bind()
    }

    /** Release the binding. Does not stop Torque. */
    fun onDestroy(context: Context? = null) {
        if (appContext == null) appContext = context?.applicationContext
        wanted = false
        handler.removeCallbacks(rebindRunnable)
        rebindPending = false
        unbind()
        torqueService = null
        conLock.withLock { onConnect.clear() }
    }

    /**
     * Ask Torque Pro to exit. Only called when the user turned on "Quit Torque when Android
     * Auto ends" and the car screen is going away for good, never on a theme change.
     */
    fun requestQuit(context: Context) {
        context.sendBroadcast(Intent("org.prowl.torque.REQUEST_TORQUE_QUIT"))
        Timber.i("Asked Torque to quit")
    }
}
