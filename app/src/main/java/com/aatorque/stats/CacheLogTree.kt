package com.aatorque.stats

import android.util.Log
import timber.log.Timber

class CacheLogTree : Timber.DebugTree() {

    companion object {
        const val KEEP_LOGS = 1_000
    }

    data class LogDesc(
        val priority: Int,
        val tag: String?,
        val message: String,
        val throwable: Throwable?
    )

    val logCache = ArrayDeque<LogDesc>(KEEP_LOGS)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (BuildConfig.DEBUG) {
            super.log(priority, "AATORQUE:${tag ?: ""}", message, t)
        }
        // Per-tick debug lines would push the lifecycle and diagnostics lines out of the
        // copied log within seconds, so only INFO and above are kept.
        if (priority < Log.INFO) return
        synchronized(logCache) {
            logCache.add(LogDesc(priority, tag, message, t))
            while (logCache.size > KEEP_LOGS) {
                logCache.removeFirst()
            }
        }
    }

    fun logToString(): Array<String> {
        val snapshot = synchronized(logCache) { logCache.toList() }
        return snapshot.map {
            return@map try {
                val level = when (it.priority) {
                    Log.ERROR -> "Error"
                    Log.DEBUG -> "Debug"
                    Log.WARN -> "Warn"
                    Log.INFO -> "Info"
                    Log.VERBOSE -> "Verbose"
                    Log.ASSERT -> "Assert"
                    else -> "Unknown"
                }
                val trace = it.throwable?.stackTraceToString() ?: ""
                "${it.tag ?: ""}: $level ${it.message} $trace"
            } catch (e: NullPointerException) {
                ""
            }
        }.toTypedArray()
    }
}
