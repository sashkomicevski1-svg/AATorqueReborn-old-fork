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

/**
 * Requests from alarms and the DPF assistant to show a given dashboard screen.
 *
 * Several conditions can ask for screens at once; the latest one still active wins. When the
 * last request ends, the car goes back to the screen it was on, unless the driver moved to
 * another one meanwhile. A request that ends is let go only after [RELEASE_DELAY_MS], and a
 * new request for the same screen in that time cancels it, so a value that hovers at a
 * threshold does not flip screens back and forth.
 *
 * The state lives here, not in the dashboard fragment, so a theme change (which replaces the
 * fragment) does not lose it.
 */
object CarScreenEvents {
    const val RELEASE_DELAY_MS = 5_000L

    sealed interface Event {
        /** Switch to [screen]. */
        data class Show(val screen: Int) : Event

        /** [screen] is no longer requested; [next] is another active request, if any. */
        data class Release(val screen: Int, val next: Int?) : Event
    }

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val flow: SharedFlow<Event> = events.asSharedFlow()

    /** Replaced in tests to observe the requests as they are made. */
    var sink: ((Event) -> Unit)? = null

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val active = LinkedHashSet<Int>()
    private val pendingRelease = HashMap<Int, Runnable>()

    /** Screen to go back to when no request is left; set by the dashboard. */
    var returnTo: Int? = null

    /** Screen the dashboard switched to for a request. */
    var jumpedTo: Int? = null

    fun show(screen: Int) {
        sink?.let {
            it(Event.Show(screen))
            return
        }
        main.post {
            pendingRelease.remove(screen)?.let { main.removeCallbacks(it) }
            active.remove(screen)
            active.add(screen)
            events.tryEmit(Event.Show(screen))
        }
    }

    fun release(screen: Int) {
        sink?.let {
            it(Event.Release(screen, null))
            return
        }
        main.post {
            if (screen !in active || pendingRelease.containsKey(screen)) return@post
            val task = Runnable {
                pendingRelease.remove(screen)
                active.remove(screen)
                events.tryEmit(Event.Release(screen, active.lastOrNull()))
            }
            pendingRelease[screen] = task
            main.postDelayed(task, RELEASE_DELAY_MS)
        }
    }

    /** A new Android Auto session starts from scratch. */
    fun reset() {
        main.post {
            pendingRelease.values.forEach { main.removeCallbacks(it) }
            pendingRelease.clear()
            active.clear()
            returnTo = null
            jumpedTo = null
        }
    }
}
