@file:Suppress("MISSING_DEPENDENCY_CLASS", "MISSING_DEPENDENCY_SUPERCLASS")

package com.aatorque.stats

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.aatorque.prefs.DevOptions
import com.aatorque.prefs.dataStore
import com.aatorque.prefs.mapTheme
import com.google.android.apps.auto.sdk.CarActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) {
            val intent = Intent("KEY_DOWN").apply {
                Timber.i("Key down $keyCode")
                putExtra("KEY_CODE", keyCode)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        Timber.i("Lifecycle: car activity onCreate")
        // Hold the Torque binding for the whole Android Auto session. Dashboard fragments come
        // and go (a theme change replaces one), and the binding must not go with them.
        torqueService = TorqueConnections.acquire(this)
        // Read the first value off the main thread. DataStore emits its default immediately,
        // so setupUi() runs in the next main-thread task.
        mainScope.launch {
            val data = dataStore.data.first()
            setLocalTheme(data.selectedTheme)
            setupUi()
        }
        mainScope.launch {
            dataStore.data.map {
                it.selectedTheme
            }.distinctUntilChanged().drop(1).collect(
                this@MainCarActivity::recreateWithTheme
            )
        }
    }

    private fun setupUi() {
        setContentView(R.layout.activity_car_main)

        // The dashboard is the only in-car screen. There is no menu: the car has one screen
        // and the phone holds the settings. See issue #166.
        val carfragment: CarFragment = DashboardFragment()
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
        val replacement = DashboardFragment()
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
        // The car screen is gone, so this is the end of the Android Auto session. Quitting
        // Torque is opt-in (Developer options), off by default.
        if (DevOptions.quitTorqueOnExit(this)) {
            torqueService?.requestQuit(this)
        }
        torqueService = null
        TorqueConnections.release()
        super.onDestroy()
    }

    companion object {
        private const val FRAGMENT_CAR = "dashboard"
    }
}
