package com.aatorque.stats

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aatorque.datastore.Coloring
import com.aatorque.datastore.Display
import com.aatorque.datastore.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Torque's per-PID read time (getPIDUpdateTime) separates a real 0 from "never read" and
 * tells when Torque stopped refreshing a PID. These tests drive applyValues directly with
 * our own clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateTimeTest {

    private fun gauge(pid: String, alarm: Coloring? = null): TorqueData {
        val display = Display.newBuilder()
            .setPid("torque_$pid")
            .setLabel(pid)
            .setMinValue(0)
            .setMaxValue(10)
            .apply { alarm?.let { addAlarms(it) } }
            .build()
        return TorqueData(display)
    }

    private fun apply(data: TorqueData, value: Double, time: Long, now: Long) {
        TorqueRefresher().applyValues(
            listOf(data),
            doubleArrayOf(value),
            longArrayOf(time),
            now
        )
    }

    @Test
    fun `a zero Torque never read stays a dash`() {
        val regen = gauge("22045b")
        apply(regen, 0.0, 0L, 1_000L)
        assertEquals("-", regen.lastDataStr)
        assertFalse(regen.hasValidRead)
    }

    @Test
    fun `a zero Torque did read is shown`() {
        val regen = gauge("22045b")
        apply(regen, 0.0, 5_000L, 1_000L)
        assertEquals("0.0", regen.lastDataStr)
        assertTrue(regen.hasValidRead)
    }

    @Test
    fun `a synthetic metric is shown even without a read time`() {
        val gpsSpeed = gauge("ff1001")
        apply(gpsSpeed, 0.0, 0L, 1_000L)
        assertEquals("0.0", gpsSpeed.lastDataStr)
    }

    @Test
    fun `an alarm on zero fires on the first confirmed read`() {
        val notRegenerating = Coloring.newBuilder()
            .setValue(0.0)
            .setOperation(Operation.EQ)
            .build()
        val regen = gauge("22045b", notRegenerating)
        apply(regen, 0.0, 5_000L, 1_000L)
        // The alarm is posted to the main thread.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0.0, regen.currentAlarm.value?.value ?: -1.0, 0.0)
    }

    @Test
    fun `a value goes stale when Torque stops refreshing it`() {
        val rpm = gauge("0c")
        // Torque reads every 200 ms.
        apply(rpm, 1.0, 10_000L, 1_000L)
        apply(rpm, 2.0, 10_200L, 1_200L)
        apply(rpm, 3.0, 10_400L, 1_400L)
        assertFalse(rpm.isStale)

        // The timestamp stops moving. The floor is 2 s because 3 x 200 ms is less.
        apply(rpm, 3.0, 10_400L, 3_300L)
        assertFalse(rpm.isStale)
        apply(rpm, 3.0, 10_400L, 3_500L)
        assertTrue(rpm.isStale)

        // Fresh data clears it.
        apply(rpm, 4.0, 13_000L, 3_600L)
        assertFalse(rpm.isStale)
    }

    @Test
    fun `a slow PID gets a longer stale window`() {
        val tyre = gauge("22c001")
        // Torque reads this one every 2 s, so stale needs 3 x 2 s.
        apply(tyre, 2.3, 10_000L, 0L)
        apply(tyre, 2.3, 12_000L, 2_000L)
        assertEquals(6_000L, tyre.staleAfterMs())
        apply(tyre, 2.3, 12_000L, 7_000L)
        assertFalse(tyre.isStale)
        apply(tyre, 2.3, 12_000L, 8_100L)
        assertTrue(tyre.isStale)
    }

    @Test
    fun `the needle is always shorter than the tick`() {
        listOf(100L, 150L, 250L, 300L, 500L).forEach { interval ->
            val needle = TorqueRefresher.needleFor(interval)
            assertTrue("$interval -> $needle", needle <= 200L)
            assertTrue("$interval -> $needle", needle < interval || interval <= 100L)
        }
    }
}
