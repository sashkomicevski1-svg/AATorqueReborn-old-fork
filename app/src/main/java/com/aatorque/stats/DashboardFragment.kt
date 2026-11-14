package com.aatorque.stats

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.InputDeviceCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.aatorque.datastore.UserPreference
import com.aatorque.prefs.BackgroundSource
import com.aatorque.prefs.BackgroundStore
import com.aatorque.prefs.DevOptions
import com.aatorque.prefs.SettingsViewModel
import com.aatorque.prefs.dataStore
import com.aatorque.prefs.parseBackground
import com.aatorque.stats.databinding.FragmentDashboardBinding
import com.aatorque.utils.CountDownLatch
import com.google.android.apps.auto.sdk.StatusBarController
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

open class DashboardFragment : AlbumArt() {
    lateinit var rootView: View
    lateinit var mLayoutDashboard: ConstraintLayout

    val torqueRefresher = TorqueRefresher()
    private val devPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val ctx = context ?: return@OnSharedPreferenceChangeListener
            when (key) {
                DevOptions.SIMULATE_VALUES ->
                    torqueRefresher.simulate = DevOptions.simulateValues(ctx)

                DevOptions.DIAGNOSTICS ->
                    torqueRefresher.diagnostics = DevOptions.diagnostics(ctx)

                DevOptions.REFRESH_INTERVAL_MS ->
                    torqueRefresher.updateInterval(DevOptions.refreshIntervalMs(ctx), torqueService)
            }
        }
    private lateinit var torqueService: TorqueService

    /**
     * True for the dashboard on the car screen. Only that one runs the car session (DPF
     * assistant, notices, screen switching); the phone-side preview does not.
     */
    val isCarScreen: Boolean
        get() = arguments?.getBoolean(ARG_CAR_SCREEN) == true

    private lateinit var mBtnNext: ImageButton
    private lateinit var mBtnPrev: ImageButton
    private lateinit var mTitleElement: TextView
    private lateinit var mWrapper: RelativeLayout
    lateinit var mConStatus: TextView

    var gauges = arrayOfNulls<TorqueGauge>(3)
    var displays = arrayOfNulls<TorqueDisplay>(4)
    var gaugeViews = arrayOfNulls<FragmentContainerView>(3)

    private var screensAnimating = false

    /** How many dashboards exist. A single dashboard has nowhere to scroll to. */
    private var screensCount = 1
    private var mStarted = false
    lateinit var binding: FragmentDashboardBinding
    lateinit var torqueChart: TorqueChart
    lateinit var settingsViewModel: SettingsViewModel

    val viewReady = CountDownLatch(1)
    private var currentScreenBackground: String = ""
    private var currentGlobalBackground: String = ""
    private var bgRequestToken: Int = 0
    private var cachedBackgroundName: String? = null
    private var cachedBackgroundBitmap: Bitmap? = null
    val albumArtReady = CountDownLatch(2)
    var shouldDisplayArtwork = false
    var displayingArtwork = false
    var albumBlurEffect: RenderEffect? = null
        set(value) {
            if (Build.VERSION.SDK_INT >= 31) {
                if (displayingArtwork) {
                    binding.blurEffect = value
                }
                field = value
            }
        }
        get() {
            return if (Build.VERSION.SDK_INT >= 31) field else null
        }
    var albumColorFilter: PorterDuffColorFilter? = null
        set(value) {
            field = value
            if (displayingArtwork) {
                binding.colorFilter = value
            }
        }

    companion object {
        const val DISPLAY_OFFSET = 3
        private const val ARG_CAR_SCREEN = "carScreen"

        /** The dashboard for the car screen. The flag is an argument, so it survives a restore. */
        fun forCar() = DashboardFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_CAR_SCREEN, true) }
        }
        private const val TORQUE_PACKAGE = "org.prowl.torque"
    }

    /** A failed bind also covers a permission refusal, so ask the package manager directly. */
    private fun isTorqueInstalled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(TORQUE_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        val context = requireContext()
        torqueRefresher.simulate = DevOptions.simulateValues(context)
        torqueRefresher.diagnostics = DevOptions.diagnostics(context)
        // The binding is shared and outlives this fragment: a theme change swaps the fragment
        // but must not drop Torque. See TorqueConnections.
        torqueService = TorqueConnections.acquire(context)
        if (isCarScreen) torqueRefresher.session = CarSession
        // The phone preview stays silent: no sounds, speech or screen switches.
        torqueRefresher.alertsEnabled = isCarScreen
        torqueRefresher.updateInterval(DevOptions.refreshIntervalMs(context), torqueService)
        DevOptions.prefs(context).registerOnSharedPreferenceChangeListener(devPrefListener)
        if (!torqueService.lastBindOk && !torqueRefresher.simulate) {
            // bindService returns false for a missing app, a missing permission and a service
            // that refuses the caller. Only the missing package is a fact worth stating.
            if (isTorqueInstalled(context)) {
                torqueRefresher.reportTorqueUnavailable()
            } else {
                torqueRefresher.reportTorqueMissing()
            }
        }
        val registerWithView = { call: suspend (Flow<UserPreference>) -> Unit ->
            lifecycleScope.launch {
                viewReady.await()
                call(context.dataStore.data)
            }
        }
        registerWithView { data ->
            data.map { prefs ->
                val screenIndex = if (prefs.screensCount > 0) {
                    abs(prefs.currentScreen) % prefs.screensCount
                } else {
                    0
                }
                val screenBg = if (prefs.screensCount > 0) {
                    prefs.screensList.getOrNull(screenIndex)?.background ?: ""
                } else {
                    ""
                }
                screenBg to prefs.selectedBackground
            }.distinctUntilChanged().collectLatest { (screen, global) ->
                applyBackground(screen, global)
                albumArtReady.countDown()
            }
        }
        registerWithView { data ->
            data.collect {
                val screenIndex = abs(it.currentScreen) % it.screensCount
                val screens = it.screensList[screenIndex]
                val showChartChanged = binding.showChart != it.showChart
                screensCount = it.screensCount
                binding.title = screens.title
                binding.showBtns = false
                // The row under the title carries the dashboard dots and the chart hint, so it
                // is shown even for a single dashboard: the swipe down works either way.
                binding.showDots = true
                binding.dashboardDots.count = it.screensCount
                binding.dashboardDots.chartHint = true
                binding.dashboardDots.selected = screenIndex
                settingsViewModel.chartVisible.value = it.showChart
                settingsViewModel.minMaxBelow.value = it.minMaxBelow
                val artworkWasOn = shouldDisplayArtwork
                shouldDisplayArtwork = it.albumArt
                if (artworkWasOn && !shouldDisplayArtwork) {
                    // applyBackground returns at once while the artwork flag is set, so
                    // clearing it here is the only path back to the dashboard background.
                    displayingArtwork = false
                    applyBackground(screens.background, it.selectedBackground)
                }

                albumArtReady.countDown()

                if (it.showChart) {
                    torqueChart.setupItems(
                        screens.gaugesList.mapIndexed { index, display ->
                            torqueRefresher.updateIfNeeded(index, screenIndex, display)
                        }.filter { data -> !data.display.hideInChart }.toTypedArray()
                    )
                } else {
                    screens.gaugesList.forEachIndexed { index, display ->
                        if (showChartChanged || torqueRefresher.hasChanged(index, display)) {
                            val clock = torqueRefresher.populateQuery(index, screenIndex, display)
                            gauges[index]?.setupClock(clock)
                        }
                    }
                }
                screens.displaysList.forEachIndexed { index, display ->
                    if (torqueRefresher.hasChanged(index + DISPLAY_OFFSET, display)) {
                        val td = torqueRefresher.populateQuery(
                            index + DISPLAY_OFFSET,
                            screenIndex,
                            display
                        )
                        displays[index]?.setupElement(td)
                    }
                }
                if (isCarScreen) {
                    torqueRefresher.setSoundWatchers(
                        it.screensList.flatMapIndexed { i, s ->
                            (s.gaugesList + s.displaysList)
                                .filter { d -> !d.disabled && d.alarmsList.any(::actsOffScreen) }
                                .map { d -> d to i }
                        }
                    )
                }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    torqueRefresher.makeExecutors(torqueService)
                }
            }
        }
        registerWithView { data ->
            data.map {
                it.opacity
            }.distinctUntilChanged().collect {
                binding.gaugeAlpha = if (it == 0) 1f else 0.01f * it
            }
        }
        registerWithView { data ->
            data.map {
                it.darkenArt
            }.distinctUntilChanged().collect {
                albumColorFilter = if (it != 0) {
                    PorterDuffColorFilter(
                        Color.valueOf(0f, 0f, 0f, it * 0.01f).toArgb(),
                        PorterDuff.Mode.DARKEN
                    )
                } else {
                    null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            registerWithView { data ->
                data.map {
                    it.blurArt
                }.distinctUntilChanged().collect {
                    albumBlurEffect = if (it != 0) {
                        val blurFloat = it.toFloat()
                        RenderEffect.createBlurEffect(
                            blurFloat,
                            blurFloat,
                            Shader.TileMode.MIRROR
                        )
                    } else {
                        null
                    }
                }
            }
        }
        registerWithView { data ->
            data.map {
                it.selectedFont
            }.distinctUntilChanged().collect(
                this@DashboardFragment::setupTypeface
            )
        }
        registerWithView { data ->
            data.map {
                it.centerGaugeLarge
            }.distinctUntilChanged().collect(
                this@DashboardFragment::updateScale
            )
        }
        registerWithView {
            torqueRefresher.connectStatus.collect {
                binding.status = when (it) {
                    ConnectStatus.CONNECTING_TORQUE -> R.string.status_connecting_torque
                    ConnectStatus.CONNECTING_ECU -> R.string.status_connecting_to_ecu
                    ConnectStatus.SETUP_GAUGE -> R.string.status_setup_gauges
                    ConnectStatus.NO_TORQUE -> R.string.status_no_torque
                    ConnectStatus.TORQUE_UNAVAILABLE -> R.string.status_torque_unavailable
                    ConnectStatus.STALE -> R.string.status_ecu_lost
                    ConnectStatus.TORQUE_LOST -> R.string.status_torque_lost
                    ConnectStatus.CONNECTED -> null
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Timber.i("onCreateView")
        binding = FragmentDashboardBinding.inflate(inflater, container, false)

        settingsViewModel.typefaceLiveData.observe(viewLifecycleOwner) {
            binding.font = it
        }
        settingsViewModel.chartVisible.observe(viewLifecycleOwner) {
            binding.showChart = it
            // The hint points at the chart, so it flips when the chart is what is on screen.
            binding.dashboardDots.chartOpen = it == true
        }
        settingsViewModel.minMaxBelow.observe(viewLifecycleOwner) {
            binding.minMaxBelow = it
        }

        rootView = binding.root

        mLayoutDashboard = rootView.findViewById(R.id.layoutDashboard)
        mBtnNext = binding.nextBtn
        mBtnPrev = binding.prevButton
        mBtnNext.setOnClickListener { setScreen(1) }
        mBtnPrev.setOnClickListener { setScreen(-1) }
        binding.chartBtn.setOnClickListener { toggleShowChart(binding.showChart != true) }
        mTitleElement = binding.textTitle
        mTitleElement.isSelected = true
        mWrapper = binding.includeWrap
        mConStatus = binding.conStatus
        gaugeViews[0] = binding.gaugeLeft
        gaugeViews[1] = binding.gaugeCenter
        gaugeViews[2] = binding.gaugeRight

        gauges[0] = childFragmentManager.findFragmentById(R.id.gaugeLeft)!! as TorqueGauge
        gauges[1] = childFragmentManager.findFragmentById(R.id.gaugeCenter)!! as TorqueGauge
        gauges[2] = childFragmentManager.findFragmentById(R.id.gaugeRight)!! as TorqueGauge
        displays[0] = childFragmentManager.findFragmentById(R.id.display1)!! as TorqueDisplay
        displays[1] = childFragmentManager.findFragmentById(R.id.display2)!! as TorqueDisplay
        displays[2] = childFragmentManager.findFragmentById(R.id.display3)!! as TorqueDisplay
        displays[3] = childFragmentManager.findFragmentById(R.id.display4)!! as TorqueDisplay
        displays[2]!!.isBottomDisplay = true
        displays[3]!!.isBottomDisplay = true
        torqueChart = childFragmentManager.findFragmentById(R.id.chartFrag)!! as TorqueChart
        viewLifecycleOwner.lifecycleScope.launch {
            CarKeyEvents.keys.collect { keyCode ->
                // A rotary-only unit reaches these four directions and nothing else, so every
                // one of them has to do something. See issue #165.
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> setScreen(-1)

                    KeyEvent.KEYCODE_DPAD_RIGHT -> setScreen(1)

                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER ->
                        toggleShowChart(binding.showChart != true)
                }
            }
        }
        val gestureDetector =
            GestureDetector(
                rootView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    // A long press on a gauge clears its min/max. A tap stays free for the
                    // swipes, which start on the gauges too.
                    override fun onLongPress(e: MotionEvent) {
                        val hit = android.graphics.Rect()
                        gaugeViews.forEachIndexed { i, container ->
                            if (container?.getGlobalVisibleRect(hit) == true &&
                                hit.contains(e.rawX.toInt(), e.rawY.toInt())
                            ) {
                                gauges[i]?.resetMinMax()
                            }
                        }
                    }

                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float
                    ): Boolean {
                        if (e1 != null) {
                            val diffX = e2.x - e1.x
                            val diffY = e2.y - e1.y

                            // Set a minimum swipe distance threshold (e.g., 100 pixels)
                            if (abs(diffX) > abs(diffY) && abs(diffX) > 100) {
                                if (diffX > 0) {
                                    // Swipe Right
                                    setScreen(-1)
                                } else {
                                    // Swipe Left
                                    setScreen(1)
                                }
                                return true
                            } else if (abs(diffY) > abs(diffX) && abs(diffY) > 100) {
                                toggleShowChart(binding.showChart != true)
                            }
                        }
                        return false
                    }
                }
            )
        rootView.setOnTouchListener { v, event ->
            val handled = gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick() // Handle accessibility
            }
            // Keep ACTION_DOWN, or the detector never sees the rest of the gesture. Every
            // event the detector did not use goes back to the framework instead of being
            // swallowed by the root view.
            handled || event.action == MotionEvent.ACTION_DOWN
        }
        configureRotaryInput()
        if (isCarScreen) {
            viewLifecycleOwner.lifecycleScope.launch {
                CarNotices.shown.collect(::showNotice)
            }
            viewLifecycleOwner.lifecycleScope.launch {
                CarScreenEvents.flow.collect(::onScreenEvent)
            }
        }
        return rootView
    }

    fun setScreen(direction: Int) {
        // With one dashboard the modulo below lands on the same screen, so the car fades out
        // and back in for nothing, which reads as a broken swipe. Stay put instead.
        if (screensAnimating || screensCount <= 1) return
        screensAnimating = true
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        mTitleElement.animate().alpha(0f).duration = duration
        mWrapper.animate()!!.translationX((rootView.width * -direction).toFloat()).setDuration(
            duration
        ).alpha(0f).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                lifecycleScope.launch {
                    requireContext().dataStore.updateData { currentSettings ->
                        currentSettings.toBuilder().setCurrentScreen(
                            (
                                currentSettings.screensCount +
                                    currentSettings.currentScreen +
                                    direction
                                ) % currentSettings.screensCount
                        ).build()
                    }
                    mWrapper.translationX = (rootView.width * direction).toFloat()
                    mWrapper.alpha = 1f
                    mWrapper.animate().setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            screensAnimating = false
                        }
                    }).translationX(0f).duration = duration
                    mTitleElement.animate().alpha(1f).duration = duration
                }
            }
        })
    }

    fun toggleShowChart(showChart: Boolean) {
        if (screensAnimating) return
        screensAnimating = true
        mWrapper.animate()!!.alpha(0f).setDuration(
            300
        ).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                mWrapper.animate()!!.alpha(1f).setDuration(300).setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            screensAnimating = false
                        }
                    }
                )
                lifecycleScope.launch {
                    context?.dataStore?.updateData { currentSettings ->
                        currentSettings.toBuilder().setShowChart(showChart).build()
                    }
                }
            }
        })
    }

    override fun setupStatusBar(sc: StatusBarController) {
        sc.hideTitle()
    }

    // Polling follows STARTED, not RESUMED: Android Auto pauses the car screen for overlays
    // and other transient states that still leave the dashboard visible.
    override fun onResume() {
        Timber.i("Lifecycle: dashboard onResume")
        super.onResume()
    }

    override fun onStart() {
        Timber.i("Lifecycle: dashboard onStart")
        super.onStart()
        lifecycleScope.launch {
            viewReady.countDown()
            torqueRefresher.makeExecutors(torqueService)
        }
    }

    override fun onStop() {
        Timber.i("Lifecycle: dashboard onStop")
        super.onStop()
        mStarted = false
        if (isCarScreen) {
            // The car dashboard keeps reading until Android Auto closes it. A stop can mean
            // another Android Auto app is in front, but possibly also a locked phone while the
            // gauges are still on the car screen; the diagnostics log tells which. Alarms with
            // sounds and the car session (DPF, trip) must go on either way.
            Timber.i("Lifecycle: car dashboard stopped; polling continues")
        } else {
            torqueRefresher.stopExecutors()
        }
    }

    override fun onPause() {
        Timber.i("Lifecycle: dashboard onPause")
        super.onPause()
    }

    override fun onDestroy() {
        Timber.i("Lifecycle: dashboard onDestroy")
        super.onDestroy()
        context?.let {
            DevOptions.prefs(it).unregisterOnSharedPreferenceChangeListener(devPrefListener)
        }
        torqueRefresher.shutdown(torqueService)
        // Releases this fragment's share of the binding only. Torque keeps running; quitting
        // it is an opt-in that only the car activity acts on when Android Auto ends.
        TorqueConnections.release()
    }

    override suspend fun onMediaChanged(medadata: MediaMetadata?) {
        Timber.i("Got new metadata $medadata shouldDisplay: $shouldDisplayArtwork")
        albumArtReady.await()
        if (!shouldDisplayArtwork) return
        if (medadata != null) {
            binding.backgroundBitmap = metaDataToArt(medadata)
            if (binding.backgroundBitmap != null) {
                // A background load already in flight must not replace this artwork when it lands.
                ++bgRequestToken
                binding.blurEffect = albumBlurEffect
                binding.colorFilter = albumColorFilter
                displayingArtwork = true
                return
            }
        }
        // The artwork is gone, so applyBackground may run: clear the flag first or it returns.
        displayingArtwork = false
        applyBackground(currentScreenBackground, currentGlobalBackground)
    }

    private fun updateScale(largeCenter: Boolean) {
        binding.largeCenter = largeCenter
    }

    private suspend fun applyBackground(screen: String, global: String) {
        currentScreenBackground = screen
        currentGlobalBackground = global
        if (displayingArtwork) return
        val token = ++bgRequestToken

        fun toSource(value: String): BackgroundSource = if (value.contains(":")) {
            parseBackground(value)
        } else if (value.isBlank()) {
            BackgroundSource.Inherit
        } else {
            BackgroundSource.Drawable(value)
        }

        val screenSource = toSource(screen)
        val globalSource = toSource(global).let {
            if (it is BackgroundSource.Inherit) {
                BackgroundSource.Drawable(
                    "background_incar_black"
                )
            } else {
                it
            }
        }

        var source = if (screenSource is BackgroundSource.Inherit) globalSource else screenSource
        if (source is BackgroundSource.Inherit) {
            source =
                BackgroundSource.Drawable("background_incar_black")
        }

        when (source) {
            is BackgroundSource.Inherit -> Unit

            is BackgroundSource.Drawable -> {
                val context = context ?: return
                val resId = resources.getIdentifier(source.name, "drawable", context.packageName)
                val finalRes = if (resId != 0) {
                    resId
                } else {
                    resources.getIdentifier(
                        "background_incar_black",
                        "drawable",
                        context.packageName
                    )
                }
                withContext(Dispatchers.Main) {
                    if (token != bgRequestToken) return@withContext
                    binding.blurEffect = null
                    binding.colorFilter = null
                    displayingArtwork = false
                    binding.backgroundBitmap = null
                    binding.backgroundResource = finalRes
                }
            }

            is BackgroundSource.Stored -> {
                val cached = if (cachedBackgroundName == source.name) {
                    cachedBackgroundBitmap
                } else {
                    cachedBackgroundName = null
                    cachedBackgroundBitmap = null
                    null
                }
                val reqHeight = if (binding.bgImageView.height > 0) {
                    binding.bgImageView.height
                } else {
                    resources.displayMetrics.heightPixels
                }
                val bitmap = cached ?: withContext(Dispatchers.IO) {
                    context?.let { BackgroundStore.resolve(it, source.name, reqHeight) }
                }
                if (token != bgRequestToken) return
                if (bitmap == null) {
                    if (screenSource is BackgroundSource.Stored) {
                        // Fall back to the global background.
                        val savedScreen = currentScreenBackground
                        applyBackground("", global)
                        currentScreenBackground = savedScreen
                    } else {
                        val context = context ?: return
                        val resId = resources.getIdentifier(
                            "background_incar_black",
                            "drawable",
                            context.packageName
                        )
                        withContext(Dispatchers.Main) {
                            if (token != bgRequestToken) return@withContext
                            binding.blurEffect = null
                            binding.colorFilter = null
                            displayingArtwork = false
                            binding.backgroundBitmap = null
                            binding.backgroundResource = resId
                        }
                    }
                    return
                }
                if (cachedBackgroundName != source.name) {
                    cachedBackgroundName = source.name
                    cachedBackgroundBitmap = bitmap
                }
                withContext(Dispatchers.Main) {
                    if (token != bgRequestToken) return@withContext
                    binding.blurEffect = null
                    binding.colorFilter = null
                    displayingArtwork = false
                    binding.backgroundResource = null
                    binding.backgroundBitmap = bitmap
                }
            }
        }
    }

    fun configureRotaryInput() {
        rootView.setOnGenericMotionListener { _, ev ->
            // A rotary knob reports AXIS_SCROLL from SOURCE_ROTARY, and some head units
            // report the same turn as SOURCE_MOUSE. Accept either, and keep the touch screen
            // out so a finger drag is not read as a turn. See issue #165.
            if (ev.action == MotionEvent.ACTION_SCROLL &&
                !ev.isFromSource(InputDeviceCompat.SOURCE_TOUCHSCREEN)
            ) {
                // A wheel reports AXIS_VSCROLL. A rotary encoder reports AXIS_SCROLL,
                // which no older wheel sets, so try it only when the vertical axis is empty.
                val vertical = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val delta = if (vertical != 0f) {
                    vertical
                } else {
                    ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                }
                if (delta == 0f) {
                    // A scroll with no axis is not a turn. Without this guard the screen
                    // would step backwards on a click.
                    false
                } else {
                    setScreen(if (delta < 0) 1 else -1)
                    true
                }
            } else {
                false
            }
        }
    }

    private fun showNotice(notice: CarNotices.Notice?) {
        val strip = binding.noticeStrip
        if (notice == null) {
            strip.visibility = View.GONE
            return
        }
        strip.text = notice.text
        val outline = when (notice.level) {
            CarNotices.Level.ACTIVE -> 0xffe3242b.toInt()
            CarNotices.Level.WARNING -> 0xfff2a900.toInt()
            CarNotices.Level.INFO -> 0xff8a8f98.toInt()
        }
        (strip.background?.mutate() as? GradientDrawable)?.setStroke(
            (2 * resources.displayMetrics.density).toInt(),
            outline
        )
        strip.visibility = View.VISIBLE
    }

    /**
     * Show the screen a DPF regeneration asked for, and go back afterwards unless the driver
     * moved to another screen meanwhile.
     */
    private suspend fun onScreenEvent(event: CarScreenEvents.Event) {
        val ctx = context ?: return
        ctx.dataStore.updateData { prefs ->
            val count = prefs.screensCount
            if (count == 0) return@updateData prefs
            val current = abs(prefs.currentScreen) % count
            val jumps = CarScreenEvents
            when (event) {
                is CarScreenEvents.Event.Show -> {
                    if (event.screen >= count) return@updateData prefs
                    // Remember where the driver was, once, before the first switch.
                    if (jumps.jumpedTo == null) {
                        jumps.returnTo = current.takeIf { it != event.screen }
                    }
                    jumps.jumpedTo = event.screen
                    if (event.screen == current) {
                        prefs
                    } else {
                        prefs.toBuilder().setCurrentScreen(event.screen).build()
                    }
                }

                is CarScreenEvents.Event.Release -> {
                    if (jumps.jumpedTo != event.screen) return@updateData prefs
                    val next = event.next?.takeIf { it < count }
                    if (current != event.screen) {
                        // The driver moved on; they are in charge now.
                        jumps.returnTo = null
                        jumps.jumpedTo = null
                        prefs
                    } else if (next != null) {
                        jumps.jumpedTo = next
                        prefs.toBuilder().setCurrentScreen(next).build()
                    } else {
                        val back = jumps.returnTo?.takeIf { it < count }
                        jumps.returnTo = null
                        jumps.jumpedTo = null
                        if (back ==
                            null
                        ) {
                            prefs
                        } else {
                            prefs.toBuilder().setCurrentScreen(back).build()
                        }
                    }
                }
            }
        }
    }

    fun setupTypeface(selectedFont: String) {
        Timber.d("font: $selectedFont")
        val font = when (selectedFont) {
            "segments" -> R.font.digital
            "seat" -> R.font.seat_metastyle_monodigit_regular
            "audi" -> R.font.auditypedisplayhigh
            "vw" -> R.font.vwtextcarui_regular
            "vw2" -> R.font.vwthesis_mib_regular
            "frutiger" -> R.font.frutiger
            "vw3" -> R.font.vw_digit_reg
            "skoda" -> R.font.skoda
            "larabie" -> R.font.larabie
            "ford" -> R.font.unitedsans
            "ev" -> R.font.ev
            "barlow" -> R.font.barlow_semi_condensed
            else -> R.font.digital
        }
        settingsViewModel.setFont(font)
    }
}

/** A rule that does something on another screen too: a sound, speech or a screen switch. */
private fun actsOffScreen(rule: com.aatorque.datastore.Coloring): Boolean =
    rule.beepOnStart || rule.beepOnEnd || rule.speakOnStart.isNotBlank() ||
        rule.speakOnEnd.isNotBlank() || rule.showScreen
