package com.aatorque.stats

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.aatorque.datastore.Coloring
import com.aatorque.prefs.SettingsViewModel
import com.aatorque.stats.databinding.FragmentDisplayBinding
import com.aatorque.utils.AwareObserver
import com.aatorque.utils.Blinker
import timber.log.Timber

class TorqueDisplay : Fragment() {
    lateinit var rootView: View
    private var unit = ""

    private val bottomBacking = MutableLiveData(false)
    var isBottomDisplay
        get() = bottomBacking.value
        set(value) {
            bottomBacking.value = value
        }
    private lateinit var binding: FragmentDisplayBinding
    lateinit var settingsViewModel: SettingsViewModel

    override fun onAttach(context: Context) {
        super.onAttach(context)
        settingsViewModel =
            ViewModelProvider(requireParentFragment())[SettingsViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Timber.i("onCreateView")
        binding = FragmentDisplayBinding.inflate(inflater, container, false)
        settingsViewModel.typefaceLiveData.observe(viewLifecycleOwner, this::setupTypeface)
        rootView = binding.root
        // Tied to the view: observeForever kept every destroyed view alive.
        bottomBacking.observe(viewLifecycleOwner) {
            binding.showBottom = it
        }
        binding.showBottom = isBottomDisplay
        settingsViewModel.chartVisible.observe(viewLifecycleOwner) {
            binding.showSide = it
        }
        return binding.root
    }

    // this sets all the labels/values in an initial state, depending on the chosen options
    fun setupElement(data: TorqueData) {
        unit = data.display.unit

        data.notifyUpdate = this::onUpdate

        var icon = data.getDrawableName() ?: "ic_none"
        binding.iconText = ""
        binding.value = ""
        binding.icon = null

        if (data.pid != null) {
            if (data.display.showLabel || data.display.icon == "" ||
                data.display.icon == "ic_none"
            ) {
                binding.iconText = data.display.label
                binding.icon = R.drawable.ic_none
            } else {
                binding.iconText = ""
                binding.icon = (
                    try {
                        resources.getIdentifier(
                            icon,
                            "drawable",
                            requireContext().packageName
                        )
                    } catch (e: Resources.NotFoundException) {
                        R.drawable.ic_none
                    }
                    )
            }
            binding.value = "-"
        }

        alarmObserver.bind(viewLifecycleOwner, data.currentAlarm)
    }

    fun setupTypeface(typeface: Typeface) {
        binding.font = typeface
    }

    @SuppressLint("SetTextI18n")
    fun onUpdate(data: TorqueData) {
        // "-" means Torque has not read this PID yet; a unit after it would read as a value.
        binding.value = if (data.lastDataStr == "-") "-" else data.lastDataStr + unit
        binding.root.alpha = if (data.isStale) STALE_ALPHA else 1f
    }

    private var blinker: Blinker? = null

    val alarmObserver = object : AwareObserver<Coloring>() {
        override fun onChanged(value: Coloring?) {
            binding.alarm = value?.color
            if (blinker == null) blinker = Blinker(binding.valueElement)
            blinker?.set(value?.blink == true)
        }
    }
}
