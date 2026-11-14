package com.aatorque.stats

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import timber.log.Timber

/**
 * Audible alarms, for rules that ask for one (Coloring.beepOnStart / beepOnEnd).
 *
 * The tones are generated, not loaded from files. They play with navigation-guidance audio
 * attributes, so on Android Auto they reach the car speakers the way a navigation prompt does
 * and music is ducked meanwhile. Whether a given head unit routes them that way is up to
 * Android Auto. Developer options → "Test alarm sound" checks it in the car.
 *
 * The same event for the same key plays at most once per [COOLDOWN_MS], so a value flickering
 * around a threshold does not beep without end. The refresh thread calls [trigger], and the
 * sound plays on its own thread, so a beep never delays a reading.
 */
object AlarmSound {
    enum class Kind { START, END }

    const val COOLDOWN_MS = 10_000L
    private const val SAMPLE_RATE = 22_050

    private var appContext: Context? = null
    private val player = Executors.newSingleThreadExecutor { r ->
        Thread(r, "alarm-sound").apply { isDaemon = true }
    }
    private val lastPlayed = HashMap<String, Long>()

    /** Replaced in tests to observe what would play. */
    @VisibleForTesting
    var sink: ((Kind) -> Unit)? = null

    @VisibleForTesting
    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Play [kind] for [key] (a PID and rule), unless the same event played just now. */
    fun trigger(key: String, kind: Kind) {
        val now = clock()
        val id = "$key|$kind"
        synchronized(lastPlayed) {
            val last = lastPlayed[id]
            if (last != null && now - last < COOLDOWN_MS) return
            lastPlayed[id] = now
        }
        Timber.i("Alarm sound $kind for $key")
        sink?.let {
            it(kind)
            return
        }
        player.execute { play(kind) }
    }

    @VisibleForTesting
    fun resetCooldowns() {
        synchronized(lastPlayed) { lastPlayed.clear() }
    }

    /** Start: three short high beeps. End: a falling two-tone, "done". */
    @VisibleForTesting
    fun pattern(kind: Kind): List<Pair<Double, Int>> = when (kind) {
        Kind.START -> listOf(1_400.0 to 140, 0.0 to 90, 1_400.0 to 140, 0.0 to 90, 1_400.0 to 140)
        Kind.END -> listOf(988.0 to 220, 0.0 to 40, 740.0 to 320)
    }

    private fun synth(kind: Kind): ShortArray {
        val parts = pattern(kind)
        val total = parts.sumOf { it.second } * SAMPLE_RATE / 1000
        val out = ShortArray(total)
        var pos = 0
        for ((freq, ms) in parts) {
            val n = ms * SAMPLE_RATE / 1000
            for (i in 0 until n) {
                if (freq > 0.0) {
                    // 8 ms fade in and out, or every tone edge clicks.
                    val fade = min(1.0, min(i, n - i) / (SAMPLE_RATE * 0.008))
                    val v = sin(2 * PI * freq * i / SAMPLE_RATE) * fade * 0.8
                    out[pos + i] = (v * Short.MAX_VALUE).toInt().toShort()
                }
            }
            pos += n
        }
        return out
    }

    private fun play(kind: Kind) {
        val context = appContext ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        val pcm = synth(kind)
        var track: AudioTrack? = null
        try {
            audio.requestAudioFocus(focus)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / SAMPLE_RATE + 150)
        } catch (e: Exception) {
            Timber.e(e, "Could not play the alarm sound")
        } finally {
            try {
                track?.stop()
            } catch (e: IllegalStateException) {
                // Never started.
            }
            track?.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
