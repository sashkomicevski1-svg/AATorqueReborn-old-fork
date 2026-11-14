package com.aatorque.stats

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources.NotFoundException
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.aatorque.datastore.Coloring
import com.aatorque.datastore.Display
import com.aatorque.datastore.MaxControl
import com.aatorque.prefs.SettingsViewModel
import com.aatorque.stats.databinding.FragmentGaugeBinding
import com.aatorque.utils.AwareObserver
import com.aatorque.utils.Blinker
import com.github.anastr.speedviewlib.RaySpeedometer
import com.github.anastr.speedviewlib.Speedometer
import com.github.anastr.speedviewlib.components.Section
import com.github.anastr.speedviewlib.components.indicators.Indicator
import com.github.anastr.speedviewlib.components.indicators.TriangleIndicator
import java.util.Locale
import kotlin.math.roundToInt
import timber.log.Timber

const val NUM_TICKS = 9
val MIN_MAX_DEFAULT = Pair(0f, 100f)
class TorqueGauge : Fragment() {

    private var rootView: View? = null
    private val mClock: TorqueSpeedometer
        get() {
            return binding.dial
        }
    private val mRayClock: RaySpeedometer
        get() {
            return binding.ray
        }
    private val mMax: Speedometer
        get() {
            return binding.dialMax
        }
    lateinit var settingsViewModel: SettingsViewModel

    private var rayOn = false
    private lateinit var binding: FragmentGaugeBinding
    private var needleGlide: NeedleGlide? = null
    private var rayGlide: NeedleGlide? = null
    private var lastShown = Float.NaN
    private var lastStale = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        settingsViewModel =
            ViewModelProvider(requireParentFragment())[SettingsViewModel::class.java]
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::binding.isInitialized) return
        outState.putBoolean("ticksOn", binding.ticksOn == true)
        outState.putInt("maxMarksOn", binding.showLimitMarked?.number ?: MaxControl.OFF_VALUE)
        outState.putInt("maxOn", binding.showLimitBelow?.number ?: MaxControl.OFF_VALUE)
        outState.putFloat("torqueMin", binding.minValue)
        outState.putFloat("torqueMax", binding.maxValue)
        outState.putBoolean("rayOn", rayOn)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Timber.i("onCreateView")
        binding = FragmentGaugeBinding.inflate(inflater, container, false)
        needleGlide = NeedleGlide(binding.dial)
        rayGlide = NeedleGlide(binding.ray)
        settingsViewModel.typefaceLiveData.observe(viewLifecycleOwner, this::setupTypeface)
        settingsViewModel.minMaxBelow.observe(viewLifecycleOwner) {
            binding.minMaxBelow = it
        }
        val view = binding.root
        rootView = view
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mMax.indicator = TriangleIndicator(requireContext())
        mMax.indicator.color = themedColor(R.attr.themedNeedleColor, Color.RED)
        mMax.clearSections()
        mMax.addSections(
            Section(
                0f,
                1f,
                android.R.color.transparent
            )
        )
        mRayClock.clearSections()
        mRayClock.addSections(
            Section(
                0f,
                1f,
                themedColor(R.attr.themedNeedleColor, Color.WHITE)
            )
        )
        savedInstanceState?.let { state ->
            setMinMax(
                state.getFloat("torqueMin", MIN_MAX_DEFAULT.first).toInt(),
                state.getFloat("torqueMax", MIN_MAX_DEFAULT.second).toInt()
            )
            turnMinMaxMarksEnabled(
                MaxControl.forNumber(
                    state.getInt(
                        "maxMarksOn",
                        MaxControl.OFF_VALUE
                    )
                )
            )
            turnMinMaxTextViewsEnabled(
                MaxControl.forNumber(
                    state.getInt(
                        "maxOn",
                        MaxControl.OFF_VALUE
                    )
                )
            )
            turnRaysEnabled(state.getBoolean("rayOn", false))
            turnTickEnabled(state.getBoolean("ticksOn", false))
        }
        mClock.invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mClock.invalidate()
        mRayClock.invalidate()
    }

    fun setupTypeface(typeface: Typeface) {
        binding.font = typeface
    }

    fun turnMinMaxMarksEnabled(enabled: MaxControl) {
        binding.showLimitMarked = enabled
    }

    fun turnMinMaxTextViewsEnabled(enabled: MaxControl) {
        binding.showLimitBelow = enabled
    }

    fun turnTickEnabled(enabled: Boolean) {
        binding.ticksOn = enabled
    }

    fun turnWholeNumbers(enabled: Boolean) {
        binding.wholeNumbers = enabled
    }

    private fun applySpeedTextFormat(display: Display) {
        mClock.speedTextListener = when (resolvedDecimalPlaces(display)) {
            0 -> { speed -> mClock.centreText(speed) { it.roundToInt().toString() } }

            2 -> { speed -> mClock.centreText(speed) { formatTwoDecimalPlaces(it) } }

            else -> { speed ->
                mClock.centreText(speed) { "%.1f".format(Locale.getDefault(), it) }
            }
        }
    }

    fun turnRaysEnabled(enabled: Boolean) {
        rayOn = enabled
        mRayClock.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        if (enabled) {
            // also hide the needle on the clocks
            mRayClock.setIndicator(Indicator.Indicators.NoIndicator)
        }

        // this is to enable an image as indicator.
        val indicatorDrawable = requireContext().theme
            .obtainStyledAttributes(intArrayOf(R.attr.themedNeedle))
            .let { attrs ->
                try {
                    attrs.getDrawable(0)
                } finally {
                    attrs.recycle()
                }
            }
        val imageIndicator = SizedImageIndicator(requireContext(), indicatorDrawable!!)
        val color = mClock.indicator.color
        Timber.i("IndicatorColor: $color")
        // if indicator color in the style is @color:aqua, make it an imageindicator
        if (color == 1996533487) {
            mClock.indicator = imageIndicator
            mRayClock.indicatorLightColor = Color.parseColor("#00FFFFFF")
        } else {
            // mClockLeft.setIndicator(Indicator.Indicators.HalfLineIndicator);
            // mClockCenter.setIndicator(Indicator.Indicators.HalfLineIndicator);
            // mClockRight.setIndicator(Indicator.Indicators.HalfLineIndicator);

            // do something to get the other type of indicator
        }

        // if rays on, turn off everything else.
        // it doesn't look too efficient at the moment, but that's to prevent the theme from adding an indicator to the rays.
        if (enabled) {
            mClock.setIndicator(Indicator.Indicators.NoIndicator)
            mRayClock.setIndicator(Indicator.Indicators.NoIndicator)

            // make indicatorlight color transparent if you don't need it:
            mClock.indicatorLightColor = Color.parseColor("#00FFFFFF")
            //
            mRayClock.indicatorLightColor = Color.parseColor("#00FFFFFF")
        } else if (color == -14575885) {
            // if theme has transparent indicator color, give clocks a custom image indicator
            // todo: do this on other fragments as well
            mClock.indicator = imageIndicator
            mClock.indicatorLightColor = Color.parseColor("#00FFFFFF")
            mRayClock.indicatorLightColor = Color.parseColor("#00FFFFFF")
        }
    }

    fun setupClock(data: TorqueData) {
        binding.visible = data.pid != null

        data.notifyUpdate = this::onUpdate
        val iconDrawableName = data.getDrawableName() ?: "ic_none"
        val iconText = if (data.display.showLabel) data.display.label else ""

        // get min/max values and unit from torque
        val context = requireContext()

        val drawable = if (iconDrawableName != "ic_none") {
            try {
                context.theme.getDrawable(
                    resources.getIdentifier(
                        iconDrawableName,
                        "drawable",
                        context.packageName
                    )
                )
            } catch (e: NotFoundException) {
                null
            }
        } else {
            null
        }

        binding.title = iconText
        mClock.unit = data.display.unit

        drawable?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
            mClock.unitTextColor,
            BlendModeCompat.SRC_ATOP
        )

        binding.icon = drawable
        setMinMax(data.display.minValue, data.display.maxValue)
        mClock.displayValue = null
        mClock.showDash = data.lastDataStr == "-"
        lastShown = Float.NaN
        needleGlide?.reset(mClock.minSpeed) ?: mClock.setSpeedAt(mClock.minSpeed)
        mMax.setSpeedAt(mMax.minSpeed)
        rayGlide?.reset(mRayClock.minSpeed) ?: mRayClock.setSpeedAt(mRayClock.minSpeed)
        binding.limitValue = "-"
        turnTickEnabled(data.display.ticksActive)
        turnMinMaxMarksEnabled(data.display.maxMarksActive)
        turnMinMaxTextViewsEnabled(data.display.maxValuesActive)
        turnRaysEnabled(data.display.highVisActive)
        turnWholeNumbers(data.display.wholeNumbers)
        applySpeedTextFormat(data.display)
        applyDigital(data)

        alarmObserver.bind(viewLifecycleOwner, data.currentAlarm)
    }

    override fun onDestroyView() {
        blinker?.set(false)
        blinker = null
        needleGlide?.cancel()
        rayGlide?.cancel()
        needleGlide = null
        rayGlide = null
        super.onDestroyView()
    }

    private fun setMinMax(minspeed: Int, maxspeed: Int) {
        val minimum = minspeed.toFloat()
        var maximum = maxspeed.toFloat()
        if (minspeed > maxspeed) {
            Timber.e("Maxspeed is not greater than minspeed min:$minspeed max:$maxspeed")
        } else if (minimum == maximum) {
            Timber.e("Maxspeed is equal to minspeed min:$minspeed max:$maxspeed")
            maximum += 1f
        }
        val minLimit = minimum.coerceAtMost(maximum)
        val maxLimit = maximum.coerceAtLeast(minimum)
        val format = if (
            (maxLimit - minLimit) < (NUM_TICKS - 1)
        ) {
            "%.1f"
        } else {
            "%.0f"
        }
        binding.minMax = Pair(minLimit, maxLimit)
        val locale = Locale.getDefault()
        binding.tickFormatter = { _, speed ->
            format.format(locale, speed)
        }
    }

    /** Reads one colour from the current theme and recycles the TypedArray. */
    private fun themedColor(attr: Int, default: Int): Int {
        val attrs = requireContext().theme.obtainStyledAttributes(intArrayOf(attr))
        return try {
            attrs.getColor(0, default)
        } finally {
            attrs.recycle()
        }
    }

    private fun onUpdate(data: TorqueData) {
        val fVal = data.lastData.toFloat()
        if (data.isStale != lastStale) {
            lastStale = data.isStale
            view?.alpha = if (data.isStale) STALE_ALPHA else 1f
        }
        if (digital) {
            showDigital(data)
            return
        }
        // Torque has not read this PID yet: keep the dash instead of drawing a 0.
        if (data.lastDataStr == "-") return
        if (mClock.showDash) {
            mClock.showDash = false
            mClock.invalidate()
        }
        // The refresh tick runs faster than Torque reads most PIDs, so most ticks bring the
        // same value. Nothing to redraw then.
        if (fVal == lastShown) return
        lastShown = fVal
        // The needle glides linearly over about one update period of this PID, so it keeps
        // moving between readings instead of jumping and stopping.
        val glideMs = NeedleGlide.durationFor(
            data.observedPeriodMs,
            TorqueRefresher.activeIntervalMs
        )
        mClock.displayValue = fVal
        needleGlide?.glideTo(fVal, glideMs)
        rayGlide?.glideTo(fVal, glideMs)
        // The glide skips a value past the dial end, so the centre text needs its own redraw.
        mClock.invalidate()
        if (data.display.maxMarksActive == MaxControl.MAX && data.maxValue.isFinite()) {
            mMax.setSpeedAt(data.maxValue.toFloat())
            Timber.d("Setting max speed ${data.maxValue}")
        } else if (data.display.maxMarksActive == MaxControl.MIN && data.minValue.isFinite()) {
            mMax.setSpeedAt(data.minValue.toFloat())
            Timber.d("Setting min speed ${data.minValue}")
        }
        if (data.display.maxValuesActive != MaxControl.OFF) {
            val possibleValue =
                if (data.display.maxValuesActive == MaxControl.MAX) data.maxValue else data.minValue
            if (possibleValue.isFinite()) {
                binding.limitValue = "%.${
                    if (possibleValue >= 1000 || data.display.wholeNumbers) '0' else '1'
                }f".format(
                    Locale.getDefault(),
                    possibleValue
                )
            }
        }
    }

    val alarmObserver = object : AwareObserver<Coloring>() {
        override fun onChanged(value: Coloring?) {
            mClock.setAlarm(value)
            // Opaque, so a rule colour with no alpha cannot make the number vanish.
            if (digital) {
                binding.digital.setTextColor((value?.color ?: Color.WHITE) or 0xFF000000.toInt())
            }
            blinker?.set(false)
            blinker = Blinker(if (digital) binding.digital else mClock).also {
                it.set(value?.blink == true)
            }
        }
    }

    /** The digital style: a large number with the unit under it, instead of the dial. */
    private var digital = false
    private var blinker: Blinker? = null

    private fun applyDigital(data: TorqueData) {
        digital = data.display.digital
        val dialViews = listOf(mClock, mRayClock, mMax)
        if (digital) {
            // The max mark and the min/max text belong to the dial. Their bindings would show
            // them again on the next frame, so turn them off through the bindings.
            binding.showLimitMarked = MaxControl.OFF
            binding.showLimitBelow = MaxControl.OFF
            lastDigitalText = null
            dialViews.forEach { it.visibility = View.INVISIBLE }
            binding.digital.visibility = View.VISIBLE
            binding.digital.setTextColor(Color.WHITE)
            showDigital(data)
        } else {
            binding.digital.visibility = View.GONE
            mClock.visibility = View.VISIBLE
            mMax.visibility = View.VISIBLE
            // The ray dial's own visibility follows its setting (turnRaysEnabled).
            mRayClock.visibility = if (rayOn) View.VISIBLE else View.INVISIBLE
        }
    }

    private var lastDigitalText: String? = null

    private fun showDigital(data: TorqueData) {
        val value = data.lastDataStr
        val unit = data.display.unit.trim()
        // Most ticks bring the same value; a new text means a layout pass and a size search.
        val key = "$value|$unit"
        if (key == lastDigitalText) return
        lastDigitalText = key
        val text = SpannableString(if (unit.isEmpty() || value == "-") value else "$value\n$unit")
        if (text.length > value.length) {
            text.setSpan(RelativeSizeSpan(0.35f), value.length, text.length, 0)
        }
        binding.digital.text = text
    }
}
