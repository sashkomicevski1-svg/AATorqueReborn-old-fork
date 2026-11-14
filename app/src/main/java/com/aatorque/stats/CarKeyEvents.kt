package com.aatorque.stats

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Key presses from the car (rotary commander, steering wheel) to the dashboard. Replaces a
 * LocalBroadcast receiver that was registered on every view creation and never unregistered.
 * The dashboard collects this for its view's lifetime only.
 */
object CarKeyEvents {
    private val mutableKeys = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val keys: SharedFlow<Int> = mutableKeys.asSharedFlow()

    fun emit(keyCode: Int) {
        mutableKeys.tryEmit(keyCode)
    }
}
