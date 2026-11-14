package com.aatorque.prefs

import android.Manifest
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.aatorque.stats.PidRole
import com.aatorque.stats.PidRoles
import com.aatorque.stats.R
import com.aatorque.stats.TorqueServiceWrapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings → DPF assistant: switches, the screen to show during a regeneration, and a manual
 * choice of PID per role for when the automatic match by name picks the wrong one.
 */
class DpfSettingsFragment : PreferenceFragmentCompat() {
    private var bound = false

    /** The trip summary is a notification; Android 13+ asks for it. */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                findPreference<CheckBoxPreference>("tripEnabled")?.summary =
                    getString(R.string.trip_no_permission)
            }
        }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val state = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (state != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private var wrapper: TorqueServiceWrapper? = null

    private val roleKeys = PidRole.entries
        .filter { it != PidRole.SPEED && it != PidRole.COOLANT }
        .map { it.key }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            wrapper = (service as TorqueServiceWrapper.LocalBinder).getService()
            wrapper?.loadPidInformation(false) { pids ->
                activity?.runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    val values = arrayOf(PidRoles.AUTO) + pids.map { it.first }
                    val names = arrayOf(getString(R.string.role_auto)) +
                        pids.map { "${it.second[0]} (${it.first.substringBefore(',')})" }
                    roleKeys.forEach { key ->
                        findPreference<ListPreference>(key)?.apply {
                            entryValues = values
                            entries = names.toList().toTypedArray()
                            if (value == null) value = PidRoles.AUTO
                            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            wrapper = null
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = PidRoles.PREFS
        addPreferencesFromResource(R.xml.dpf_settings)

        roleKeys.forEach { key ->
            findPreference<ListPreference>(key)?.apply {
                // Until Torque's list arrives, only "automatic" can be chosen.
                entries = arrayOf(getString(R.string.role_auto))
                entryValues = arrayOf(PidRoles.AUTO)
                if (value == null) value = PidRoles.AUTO
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        }

        val screenPref = findPreference<ListPreference>("dpfScreen")!!
        screenPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        screenPref.entries = arrayOf(getString(R.string.dpf_screen_none))
        screenPref.entryValues = arrayOf("-1")
        lifecycleScope.launch {
            val prefs = requireContext().dataStore.data.first()
            val titles = prefs.screensList.mapIndexed { i, s ->
                s.title.ifBlank { getString(R.string.pref_data_element_settings, i + 1) }
            }
            screenPref.entries =
                (listOf(getString(R.string.dpf_screen_none)) + titles).toTypedArray()
            screenPref.entryValues =
                (listOf("-1") + titles.indices.map { it.toString() }).toTypedArray()
            if (screenPref.value == null) screenPref.value = "-1"
        }

        val tripPref = findPreference<CheckBoxPreference>("tripEnabled")!!
        if (tripPref.isChecked) requestNotificationsIfNeeded()
        tripPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) requestNotificationsIfNeeded()
            true
        }

        bound = TorqueServiceWrapper.runStartIntent(requireContext(), connection)
    }

    override fun onDestroy() {
        if (bound) {
            requireContext().unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
