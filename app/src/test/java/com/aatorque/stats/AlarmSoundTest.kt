package com.aatorque.stats

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aatorque.datastore.Coloring
import com.aatorque.datastore.Display
import com.aatorque.datastore.Operation
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.prowl.torque.remote.ITorqueService
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Audible alarms, driven by the DPF regeneration case: beep when a regeneration starts, and
 * again when it ends so the engine can be switched off.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmSoundTest {
    private val played = mutableListOf<AlarmSound.Kind>()
    private var now = 0L

    @Before
    fun setUp() {
        AlarmSound.resetCooldowns()
        AlarmSound.sink = { played.add(it) }
        AlarmSound.clock = { now }
    }

    private val spoken = mutableListOf<String>()
    private val screens = mutableListOf<CarScreenEvents.Event>()

    @Before
    fun hookVoiceAndScreens() {
        VoiceAlerts.sink = { spoken.add(it) }
        CarScreenEvents.sink = { screens.add(it) }
    }

    @After
    fun tearDown() {
        VoiceAlerts.sink = null
        CarScreenEvents.sink = null
        AlarmSound.sink = null
        AlarmSound.clock = { android.os.SystemClock.elapsedRealtime() }
        AlarmSound.resetCooldowns()
    }

    private val regenRule = Coloring.newBuilder()
        .setOperation(Operation.EQ)
        .setValue(1.0)
        .setColor(0xffff0000.toInt())
        .setBeepOnStart(true)
        .setBeepOnEnd(true)
        .build()

    private fun regen(rule: Coloring = regenRule): TorqueData {
        val display = Display.newBuilder()
            .setPid("torque_22f48b,115616640")
            .setLabel("DPF REGEN")
            .setMinValue(0)
            .setMaxValue(1)
            .addAlarms(rule)
            .build()
        return TorqueData(display)
    }

    /** One Torque reading at [atMs], with a fresh Torque timestamp, so it counts as data. */
    private fun read(data: TorqueData, value: Double, atMs: Long) {
        now = atMs
        TorqueRefresher().applyValues(
            listOf(data),
            doubleArrayOf(value),
            longArrayOf(atMs + 1),
            atMs
        )
    }

    @Test
    fun `a regeneration beeps when it starts and when it ends`() {
        val data = regen()
        read(data, 0.0, 0)
        read(data, 1.0, 60_000)
        read(data, 1.0, 61_000)
        read(data, 0.0, 600_000)
        assertEquals(listOf(AlarmSound.Kind.START, AlarmSound.Kind.END), played)
    }

    @Test
    fun `a regeneration already running at start-up beeps`() {
        val data = regen()
        read(data, 1.0, 0)
        assertEquals(listOf(AlarmSound.Kind.START), played)
    }

    @Test
    fun `no end beep at start-up`() {
        val data = regen()
        read(data, 0.0, 0)
        read(data, 0.0, 1_000)
        assertEquals(emptyList<AlarmSound.Kind>(), played)
    }

    @Test
    fun `a flickering value does not beep without end`() {
        val data = regen()
        read(data, 0.0, 0)
        read(data, 1.0, 1_000)
        read(data, 0.0, 2_000)
        read(data, 1.0, 3_000)
        read(data, 0.0, 4_000)
        assertEquals(listOf(AlarmSound.Kind.START, AlarmSound.Kind.END), played)
    }

    @Test
    fun `a silent rule stays silent`() {
        val data = regen(regenRule.toBuilder().setBeepOnStart(false).setBeepOnEnd(false).build())
        read(data, 0.0, 0)
        read(data, 1.0, 60_000)
        read(data, 0.0, 120_000)
        assertEquals(emptyList<AlarmSound.Kind>(), played)
    }

    @Test
    fun `a rule with text speaks instead of beeping`() {
        val data = regen(
            regenRule.toBuilder()
                .setSpeakOnStart("DPF regeneration started")
                .setSpeakOnEnd("You can switch off now")
                .build()
        )
        read(data, 0.0, 0)
        read(data, 1.0, 60_000)
        read(data, 0.0, 600_000)
        assertEquals(listOf("DPF regeneration started", "You can switch off now"), spoken)
        assertEquals(emptyList<AlarmSound.Kind>(), played)
    }

    @Test
    fun `a rule can show its screen while it lasts`() {
        val data = regen(regenRule.toBuilder().setShowScreen(true).build())
        data.screenIndex = 1
        read(data, 0.0, 0)
        read(data, 1.0, 60_000)
        read(data, 0.0, 600_000)
        assertEquals(
            listOf(CarScreenEvents.Event.Show(1), CarScreenEvents.Event.Release(1)),
            screens
        )
    }

    @Test
    fun `an old backup reads the new flags as off`() {
        val old = Coloring.newBuilder().setOperation(Operation.EQ).setValue(1.0).build()
        val parsed = Coloring.parseFrom(old.toByteArray())
        assertFalse(parsed.beepOnStart)
        assertFalse(parsed.beepOnEnd)
        assertEquals(regenRule, Coloring.parseFrom(regenRule.toByteArray()))
    }

    @Test
    fun `a sound alarm on another screen is still read`() {
        val asked = mutableListOf<List<String>>()
        val fake = Proxy.newProxyInstance(
            ITorqueService::class.java.classLoader,
            arrayOf(ITorqueService::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)

                "hashCode" -> 0

                "toString" -> "FakeTorque"

                "isConnectedToECU" -> true

                "getPIDValuesAsDouble" -> {
                    @Suppress("UNCHECKED_CAST")
                    val pids = (args!![0] as Array<String>).toList()
                    asked.add(pids)
                    pids.map { if (it.startsWith("22f48b")) 1.0 else 50.0 }.toDoubleArray()
                }

                "getPIDUpdateTime" -> LongArray((args!![0] as Array<*>).size) { 5_000L }

                else -> throw UnsupportedOperationException(method.name)
            }
        } as ITorqueService
        val service = TorqueService().apply { torqueService = fake }
        val refresher = TorqueRefresher()
        // The visible screen shows tyre pressure only; DPF REGEN lives on another screen.
        refresher.data[0] = TorqueData(
            Display.newBuilder().setPid("torque_222a05,470605887").setMaxValue(4).build()
        )
        refresher.setSoundWatchers(listOf(regen().display to 0))

        refresher.refreshDashboard(service)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("222a05,470605887", "22f48b,115616640"), asked.single())
        assertEquals(listOf(AlarmSound.Kind.START), played)
        service.onDestroy()
    }
}
