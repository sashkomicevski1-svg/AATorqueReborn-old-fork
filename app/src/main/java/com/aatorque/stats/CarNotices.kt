package com.aatorque.stats

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Short messages for the strip at the bottom of the car screen: a DPF regeneration in
 * progress, a regeneration that was cut short, fault codes. Several sources post under their
 * own key. The strip shows the highest priority one.
 */
object CarNotices {
    enum class Level { INFO, ACTIVE, WARNING }

    data class Notice(
        val text: String,
        val level: Level,
        /** Higher wins when several notices are posted. */
        val priority: Int,
        /** Elapsed-realtime when it goes away by itself; 0 = until removed. */
        val expiresAt: Long = 0L
    )

    private val notices = LinkedHashMap<String, Notice>()
    private val current = MutableStateFlow<Notice?>(null)
    val shown: StateFlow<Notice?> = current.asStateFlow()

    fun post(key: String, notice: Notice) = synchronized(notices) {
        notices[key] = notice
        publish()
    }

    fun remove(key: String) = synchronized(notices) {
        if (notices.remove(key) != null) publish()
    }

    /** Drops expired notices; the refresh tick calls this. */
    fun expire(now: Long) = synchronized(notices) {
        val before = notices.size
        notices.entries.removeAll { it.value.expiresAt in 1..now }
        if (notices.size != before) publish()
    }

    fun clear() = synchronized(notices) {
        notices.clear()
        publish()
    }

    private fun publish() {
        current.value = notices.values.maxByOrNull { it.priority }
    }
}

/** Requests from alarms and the DPF assistant to show a given dashboard screen. */
object CarScreenEvents {
    sealed interface Event {
        /** Show [screen] while the condition lasts. */
        data class Show(val screen: Int) : Event

        /** The condition is over; go back if the driver has not moved on meanwhile. */
        data class Release(val screen: Int) : Event
    }

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val flow: SharedFlow<Event> = events.asSharedFlow()

    /** Replaced in tests to observe the requests. */
    var sink: ((Event) -> Unit)? = null

    fun show(screen: Int) = emit(Event.Show(screen))

    fun release(screen: Int) = emit(Event.Release(screen))

    private fun emit(event: Event) {
        sink?.let {
            it(event)
            return
        }
        events.tryEmit(event)
    }
}
