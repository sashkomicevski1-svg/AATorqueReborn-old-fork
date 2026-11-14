package com.aatorque.stats

import android.content.Context
import androidx.annotation.MainThread
import timber.log.Timber

/**
 * One Torque binding for the whole process, shared by reference count.
 *
 * The car activity holds a reference for the whole Android Auto session, so a dashboard
 * fragment that is swapped for a theme change never drops the binding. The phone-side preview
 * takes its own reference while it is open. When the last owner lets go the binding is
 * released; Torque itself keeps running.
 */
object TorqueConnections {
    private var service: TorqueService? = null
    private var owners = 0

    @MainThread
    fun acquire(context: Context): TorqueService {
        val current = service ?: TorqueService().also {
            service = it
            it.startTorque(context)
        }
        owners++
        Timber.d("Torque connection acquired, owners=$owners")
        return current
    }

    @MainThread
    fun release() {
        if (owners == 0) return
        owners--
        Timber.d("Torque connection released, owners=$owners")
        if (owners == 0) {
            service?.onDestroy()
            service = null
        }
    }
}
