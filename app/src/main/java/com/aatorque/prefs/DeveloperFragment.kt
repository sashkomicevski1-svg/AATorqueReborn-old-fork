package com.aatorque.prefs

import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.aatorque.stats.R

/**
 * Developer options. Every tool is a direct switch; there is no master switch.
 */
class DeveloperFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.developer)
        val context = requireContext()
        val simulate = findPreference<CheckBoxPreference>(DevOptions.SIMULATE_VALUES)!!
        simulate.isChecked = DevOptions.simulateValues(context)
        simulate.setOnPreferenceChangeListener { _, newValue ->
            DevOptions.setSimulateValues(requireContext(), newValue as Boolean)
            true
        }

        bindCheckBox(DevOptions.QUIT_TORQUE_ON_EXIT, DevOptions.quitTorqueOnExit(context))
        bindCheckBox(DevOptions.DIAGNOSTICS, DevOptions.diagnostics(context))

        val refresh = findPreference<ListPreference>(DevOptions.REFRESH_INTERVAL_MS)!!
        refresh.value = DevOptions.refreshIntervalMs(context).toString()
        refresh.summary = getString(R.string.developer_refresh_summary, refresh.value)
        refresh.setOnPreferenceChangeListener { pref, newValue ->
            DevOptions.prefs(requireContext()).edit()
                .putString(DevOptions.REFRESH_INTERVAL_MS, newValue as String).apply()
            pref.summary = getString(R.string.developer_refresh_summary, newValue)
            true
        }
    }

    private fun bindCheckBox(key: String, current: Boolean) {
        val pref = findPreference<CheckBoxPreference>(key)!!
        pref.isChecked = current
        pref.setOnPreferenceChangeListener { _, newValue ->
            DevOptions.prefs(requireContext()).edit().putBoolean(key, newValue as Boolean).apply()
            true
        }
    }
}
