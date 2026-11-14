package com.aatorque.stats

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

/**
 * Spoken alerts ("DPF regeneration started"), through the phone's text-to-speech engine.
 *
 * They use navigation-guidance audio like [AlarmSound], so on Android Auto they should play
 * on the car speakers with the music ducked. The language is the text-to-speech engine's
 * default. Texts typed into an alarm rule are spoken as written.
 */
object VoiceAlerts {
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private val ids = AtomicInteger()
    private var focus: AudioFocusRequest? = null
    private var outstanding = 0
    private var failedAt = 0L
    private const val RETRY_MS = 60_000L

    /** Replaced in tests to observe what would be spoken. */
    @VisibleForTesting
    var sink: ((String) -> Unit)? = null

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun speak(text: String) {
        if (text.isBlank()) return
        Timber.i("Voice: $text")
        sink?.let {
            it(text)
            return
        }
        val context = appContext ?: return
        if (tts == null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (failedAt != 0L && now - failedAt < RETRY_MS) {
                Timber.w("Voice: text-to-speech failed recently; not spoken")
                return
            }
            tts = TextToSpeech(context) { status -> onInit(status) }
        }
        if (ready) say(text) else pending.add(text)
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Timber.e("Text-to-speech is not available (status $status)")
            pending.clear()
            // Try again with a new engine later instead of queueing for ever.
            engine.shutdown()
            tts = null
            ready = false
            failedAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = utteranceFinished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = utteranceFinished()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = utteranceFinished()
        })
        ready = true
        pending.forEach(::say)
        pending.clear()
    }

    private fun say(text: String) {
        val engine = tts ?: return
        val context = appContext ?: return
        val audio = context.getSystemService(AudioManager::class.java)
        if (focus == null && audio != null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
                .also { audio.requestAudioFocus(it) }
        }
        outstanding++
        val result =
            engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "alert-${ids.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) utteranceFinished()
    }

    /** Give the audio focus back (un-duck the music) when the last queued text is done. */
    @Synchronized
    private fun utteranceFinished() {
        outstanding = (outstanding - 1).coerceAtLeast(0)
        if (outstanding > 0) return
        val request = focus ?: return
        focus = null
        appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }
}
