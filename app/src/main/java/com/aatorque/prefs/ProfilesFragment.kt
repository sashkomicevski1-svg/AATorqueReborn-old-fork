package com.aatorque.prefs

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.aatorque.stats.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Settings → Car profiles: save the current setup under a name, load a saved one in its
 * place, or delete one. Loading replaces dashboards, theme, font, background and alarms.
 */
class ProfilesFragment : PreferenceFragmentCompat() {
    private lateinit var store: ProfileStore
    private lateinit var listCategory: PreferenceCategory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        store = ProfileStore(File(context.filesDir, "profiles"))
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(
            Preference(context).apply {
                key = "saveProfile"
                title = getString(R.string.profile_save)
                summary = getString(R.string.profile_save_summary)
                isPersistent = false
                setOnPreferenceClickListener {
                    askName()
                    true
                }
            }
        )
        listCategory = PreferenceCategory(context).apply {
            title = getString(R.string.profile_saved)
            isPersistent = false
        }
        screen.addPreference(listCategory)
        preferenceScreen = screen
        refresh()
    }

    private fun refresh() {
        listCategory.removeAll()
        val names = store.list()
        if (names.isEmpty()) {
            listCategory.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.profile_none)
                    isSelectable = false
                    isPersistent = false
                }
            )
        }
        names.forEach { name ->
            listCategory.addPreference(
                Preference(requireContext()).apply {
                    title = name
                    isPersistent = false
                    setOnPreferenceClickListener {
                        choose(name)
                        true
                    }
                }
            )
        }
    }

    private fun askName() {
        val input = EditText(requireContext()).apply { setSingleLine() }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_save)
            .setView(input)
            .setPositiveButton(R.string.profile_save_button) { _, _ ->
                val name = input.text.toString()
                lifecycleScope.launch {
                    val prefs = requireContext().dataStore.data.first()
                    val saved = withContext(Dispatchers.IO) { store.save(name, prefs) }
                    if (saved == null) {
                        Toast.makeText(
                            requireContext(),
                            R.string.profile_bad_name,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.profile_saved_as, saved),
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun choose(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(
                arrayOf(getString(R.string.profile_load), getString(R.string.profile_delete))
            ) { _, which ->
                if (which == 0) confirmLoad(name) else confirmDelete(name)
            }
            .show()
    }

    private fun confirmLoad(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_load_title, name))
            .setMessage(R.string.profile_load_message)
            .setPositiveButton(R.string.profile_load) { _, _ ->
                lifecycleScope.launch {
                    val context = requireContext()
                    val loaded = withContext(Dispatchers.IO) {
                        try {
                            store.load(name)
                        } catch (e: Exception) {
                            Timber.e(e, "Profile $name could not be read")
                            null
                        }
                    }
                    if (loaded == null) {
                        Toast.makeText(
                            context,
                            R.string.profile_load_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    context.dataStore.updateData { loaded }
                    Toast.makeText(
                        context,
                        getString(R.string.profile_loaded, name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_delete_title, name))
            .setPositiveButton(R.string.profile_delete) { _, _ ->
                store.delete(name)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
