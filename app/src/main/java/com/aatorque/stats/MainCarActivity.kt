@file:Suppress("MISSING_DEPENDENCY_CLASS", "MISSING_DEPENDENCY_SUPERCLASS")

package com.aatorque.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.aatorque.prefs.DevOptions
import com.aatorque.prefs.dataStore
import com.aatorque.prefs.mapTheme
import com.google.android.apps.auto.sdk.CarActivity
import java.util.Calendar
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

class MainCarActivity : CarActivity() {
    // MainScope because the compiler cannot see that CarActivity is a LifecycleOwner:
    // its superclass is a missing dependency (see the file-level suppression).
    private val mainScope = MainScope()
    private var dashboard: CarFragment? = null
    private var inBg = false
    private var awaitingTheme: String? = null
    private var lastTheme: String? = null
    private var torqueService: TorqueService? = null
    private var sessionToken = 0

    /** The car's night mode as Android Auto reports it in the configuration. */
    private var carNight = false

    /**
     * [carNight] once it has held for [NIGHT_SETTLE_MS]. A tunnel with automatic headlights
     * must not rebuild the dashboard on the way in and again on the way out.
     */
    private var carNightStable = false
    private var nightSettle: kotlinx.coroutines.Job? = null

    /** Night for the theme: the car's night mode, or the clock when chosen. */
    private val night = MutableStateFlow(false)

    /** Logs lock and unlock, so the diagnostics show what the phone did around a stall. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Timber.i("Screen: ${intent?.action?.substringAfterLast('.')}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) {
            Timber.i("Key down $keyCode")
            CarKeyEvents.emit(keyCode)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        Timber.i("Lifecycle: car activity onCreate")
        // Hold the Torque binding for the whole Android Auto session. Dashboard fragments come
        // and go (a theme change replaces one), and the binding must not go with them.
        torqueService = TorqueConnections.acquire(this)
        sessionToken = CarSession.start(this)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            applicationContext,
            screenReceiver,
            screenFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Read the first value off the main thread. DataStore emits its default immediately,
        // so setupUi() runs in the next main-thread task.
        carNight = NightTheme.isNightConfig(resources.configuration)
        carNightStable = carNight
        Timber.i("Night: car night mode at start $carNight")
        mainScope.launch {
            val data = dataStore.data.first()
            night.value = NightTheme.isNight(data, carNightStable, hour())
            setLocalTheme(NightTheme.effective(data, night.value))
            setupUi()
        }
        mainScope.launch {
            combine(dataStore.data, night) { prefs, isNight ->
                NightTheme.effective(prefs, isNight)
            }
                .distinctUntilChanged()
                .drop(1)
                .collect(this@MainCarActivity::recreateWithTheme)
        }
        // The clock is checked once a minute; the car's night mode arrives as a configuration
        // change (onConfigurationChanged).
        mainScope.launch {
            while (true) {
                updateNight()
                delay(60_000)
            }
        }
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private suspend fun updateNight() {
        val prefs = dataStore.data.first()
        val isNight = NightTheme.isNight(prefs, carNightStable, hour())
        if (isNight != night.value) Timber.i("Night: now $isNight")
        night.value = isNight
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = NightTheme.isNightConfig(newConfig)
        if (now != carNight) {
            Timber.i("Night: car night mode $now")
            carNight = now
            nightSettle?.cancel()
            nightSettle = mainScope.launch {
                delay(NIGHT_SETTLE_MS)
                carNightStable = carNight
                updateNight()
            }
        }
    }

    private fun setupUi() {
        setContentView(R.layout.activity_car_main)

        // The dashboard is the only in-car screen. There is no menu: the car has one screen
        // and the phone holds the settings. See issue #166.
        val carfragment: CarFragment = DashboardFragment.forCar()
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, carfragment, FRAGMENT_CAR)
            .commitNow()
        dashboard = carfragment
        carfragment.setupStatusBar(carUiController.statusBarController)
        setIgnoreConfigChanges(0xFFFF)
    }

    override fun onSaveInstanceState(bundle: Bundle) {
        inBg = true
        super.onSaveInstanceState(bundle)
    }

    fun recreateWithTheme(selectedTheme: String) {
        if (inBg) {
            awaitingTheme = selectedTheme
            return
        }
        if (!setLocalTheme(selectedTheme)) return

        val current = dashboard ?: return
        val replacement = DashboardFragment.forCar()
        supportFragmentManager.beginTransaction()
            .remove(current)
            .add(R.id.fragment_container, replacement, FRAGMENT_CAR)
            .commit()
        dashboard = replacement
    }

    private fun setLocalTheme(theme: String?): Boolean {
        if (lastTheme != theme) {
            lastTheme = theme
            setTheme(mapTheme(this, theme))
            return true
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        Timber.i("Lifecycle: car activity onStart")
    }

    override fun onStop() {
        Timber.i("Lifecycle: car activity onStop")
        super.onStop()
    }

    override fun onPause() {
        Timber.i("Lifecycle: car activity onPause")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Timber.i("Lifecycle: car activity onResume")
        inBg = false
        awaitingTheme?.let(this::recreateWithTheme)
        awaitingTheme = null
    }

    override fun onDestroy() {
        Timber.i("Lifecycle: car activity onDestroy")
        mainScope.cancel()
        CarSession.end(sessionToken)
        // The car screen is gone, so this is the end of the Android Auto session. Quitting
        // Torque is opt-in (Developer options), off by default.
        // Release first: a binding with BIND_AUTO_CREATE that is still held would let Android
        // start Torque again right after it quits.
        val quit = torqueService
        torqueService = null
        TorqueConnections.release()
        if (DevOptions.quitTorqueOnExit(this)) {
            quit?.requestQuit(this)
        }
        try {
            applicationContext.unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Screen receiver was not registered")
        }
        super.onDestroy()
    }

    companion object {
        private const val FRAGMENT_CAR = "dashboard"
        private const val NIGHT_SETTLE_MS = 30_000L
    }
}
