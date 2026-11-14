package com.aatorque.stats

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aatorque.datastore.Coloring
import com.aatorque.datastore.Display
import com.aatorque.datastore.Operation
import com.aatorque.prefs.DriveLogCsv
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SafeGroupTest {
    private var now = 0L

    @Before
    fun setUp() {
        TorqueData.clock = { now }
        AlarmSound.sink = {}
        AlarmSound.resetCooldowns()
    }

    @After
    fun tearDown() {
        TorqueData.clock = { android.os.SystemClock.elapsedRealtime() }
        AlarmSound.sink = null
    }

    private fun coolant(vararg rules: Coloring): TorqueData {
        val display = Display.newBuilder()
            .setPid("torque_05,0")
            .setMinValue(-40)
            .setMaxValue(120)
            .addAllAlarms(rules.toList())
            .build()
        return TorqueData(display)
    }

    private fun read(data: TorqueData, value: Double, at: Long) {
        now = at
        TorqueRefresher().applyValues(listOf(data), doubleArrayOf(value), longArrayOf(at + 1), at)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val red = Coloring.newBuilder().setOperation(Operation.GT).setValue(110.0)
        .setColor(0xffff0000.toInt()).setHoldSeconds(10).build()
    private val amber = Coloring.newBuilder().setOperation(Operation.GT).setValue(100.0)
        .setColor(0xfff2a900.toInt()).build()

    @Test
    fun `a rule with a hold time waits, a lower ready rule applies meanwhile`() {
        val data = coolant(red, amber)
        read(data, 112.0, 0)
        assertEquals(amber, data.currentAlarm.value)
        read(data, 112.0, 9_000)
        assertEquals(amber, data.currentAlarm.value)
        read(data, 112.0, 10_000)
        assertEquals(red, data.currentAlarm.value)
    }

    @Test
    fun `a short spike does not trigger a held rule`() {
        val data = coolant(red)
        read(data, 115.0, 0)
        read(data, 105.0, 3_000)
        read(data, 115.0, 6_000)
        read(data, 115.0, 12_000)
        assertNull(data.currentAlarm.value)
        read(data, 115.0, 16_000)
        assertEquals(red, data.currentAlarm.value)
    }

    @Test
    fun `csv fields are quoted when needed`() {
        assertEquals("plain", DriveLogCsv.field("plain"))
        assertEquals("\"a,b\"", DriveLogCsv.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", DriveLogCsv.field("say \"hi\""))
    }

    @Test
    fun `the zip holds the four tables`() {
        val trip = TripRecord(
            startWall = 1_700_000_000_000,
            durationMs = 1_800_000,
            distanceKm = 23.4,
            movingMs = 1_500_000,
            maxSpeed = 120.0,
            maxCoolant = 92.0,
            regenerations = 1,
            regenCompleted = 1,
            faultCodes = listOf("P2002")
        )
        val regen =
            RegenRecord(startWall = 1_700_000_100_000, outcome = RegenRecord.Outcome.COMPLETED)
        val out = ByteArrayOutputStream()
        DriveLogCsv.writeZip(
            out,
            listOf(trip),
            listOf(regen),
            listOf(AccelResult(AccelTimer.Kind.ZERO_100, 9.8, 1_700_000_200_000)),
            DtcReading(1_700_000_300_000, listOf(Dtc("P2002", false)))
        )
        val files = HashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(
            setOf("trips.csv", "dpf_regenerations.csv", "acceleration.csv", "fault_codes.csv"),
            files.keys
        )
        assertTrue(files["trips.csv"]!!.contains("2023-11-14T22:13:20Z,30.000,23.400"))
        assertTrue(files["dpf_regenerations.csv"]!!.contains("COMPLETED"))
        assertTrue(files["acceleration.csv"]!!.contains("ZERO_100,9.800"))
        assertTrue(
            files["fault_codes.csv"]!!.contains("P2002,false,DPF efficiency below threshold")
        )
    }

    @Test
    fun `older backups keep every gauge in the chart and rules without hold`() {
        val old = Display.newBuilder().setPid("torque_0c,0")
            .addAlarms(Coloring.newBuilder().setValue(1.0)).build()
        val parsed = Display.parseFrom(old.toByteArray())
        assertFalse(parsed.hideInChart)
        assertEquals(0, parsed.getAlarms(0).holdSeconds)
    }
}
