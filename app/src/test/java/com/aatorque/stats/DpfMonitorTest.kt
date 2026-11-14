package com.aatorque.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DpfMonitorTest {

    private class Outputs : DpfOutputs {
        val notices = HashMap<String, CarNotices.Notice?>()
        val spoken = mutableListOf<String>()
        val shown = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        override fun notice(key: String, notice: CarNotices.Notice?) {
            notices[key] = notice
        }

        override fun speak(text: String) {
            spoken.add(text)
        }

        override fun showScreen(screen: Int) {
            shown.add(screen)
        }

        override fun releaseScreen(screen: Int) {
            released.add(screen)
        }
    }

    private val dir: File = Files.createTempDirectory("dpf").toFile()
    private val history = CarSession.run {
        JsonHistory(File(dir, "h.json"), 200, RegenRecord::toJson, RegenRecord::fromJson)
    }

    private fun readings(
        status: Double,
        soot: Double,
        total: Double,
        trigger: Double? = null,
        count: Double = 40.0
    ) = buildMap {
        put(PidRole.REGEN_STATUS, status)
        put(PidRole.SOOT, soot)
        put(PidRole.TOTAL_DISTANCE, total)
        put(PidRole.REGEN_COUNT, count)
        put(PidRole.DISTANCE_SINCE_REGEN, 412.0)
        trigger?.let { put(PidRole.REGEN_TRIGGER, it) }
    }

    @Test
    fun `a full regeneration is recorded as completed`() {
        val out = Outputs()
        val m = DpfMonitor(history, out, screenDuringRegen = 1)
        m.update(readings(0.0, 23.5, 100_000.0), 0, 0)
        m.update(readings(1.0, 24.0, 100_001.0, trigger = 10.0), 1_000, 1_000)
        assertTrue(m.isActive)
        assertEquals(CarNotices.Level.ACTIVE, out.notices[DpfMonitor.KEY_ACTIVE]?.level)
        m.update(readings(1.0, 12.0, 100_008.0, trigger = 70.0), 400_000, 400_000)
        assertTrue(out.notices[DpfMonitor.KEY_ACTIVE]!!.text.contains("7.0 km"))
        m.update(readings(0.0, 2.0, 100_012.5, count = 41.0), 700_000, 700_000)

        assertFalse(m.isActive)
        assertNull(out.notices[DpfMonitor.KEY_ACTIVE])
        val r = history.load().single()
        assertEquals(RegenRecord.Outcome.COMPLETED, r.outcome)
        assertEquals(11.5, r.distanceKm, 0.001)
        assertEquals(412.0, r.intervalKm!!, 0.0)
        assertEquals(70.0, r.triggerMax!!, 0.0)
        assertEquals(699_000L, r.durationMs)
        assertEquals(listOf(DpfTexts().started, DpfTexts().finished), out.spoken)
        assertEquals(listOf(1), out.shown)
        assertEquals(listOf(1), out.released)
    }

    @Test
    fun `a regeneration that ends with soot still high is paused`() {
        val out = Outputs()
        val m = DpfMonitor(history, out)
        m.update(readings(1.0, 24.0, 100_000.0), 0, 0)
        m.update(readings(0.0, 20.0, 100_003.0), 120_000, 120_000)
        assertEquals(RegenRecord.Outcome.PAUSED, history.load().single().outcome)
        assertEquals(DpfTexts().paused, out.spoken.last())
    }

    @Test
    fun `engine off during a regeneration is reported at the next start`() {
        val out = Outputs()
        val m = DpfMonitor(history, out)
        m.update(readings(1.0, 24.0, 100_000.0), 0, 0)
        m.update(readings(1.0, 18.0, 100_004.0), 300_000, 300_000)
        m.onSessionEnd(310_000, 310_000)
        assertEquals(RegenRecord.Outcome.OPEN_AT_SHUTDOWN, history.load().single().outcome)
        assertEquals(4.0, history.load().single().distanceKm, 0.001)

        // Next drive: a new monitor, soot still high.
        val next = Outputs()
        val m2 = DpfMonitor(history, next)
        m2.update(readings(0.0, 18.5, 100_004.0), 1_000_000, 5_000)
        assertEquals(RegenRecord.Outcome.INTERRUPTED, history.load().single().outcome)
        assertEquals(CarNotices.Level.WARNING, next.notices[DpfMonitor.KEY_WARNING]?.level)
        assertEquals(listOf(DpfTexts().interrupted), next.spoken)
    }

    @Test
    fun `engine off near the end, soot already low, counts as completed`() {
        val m = DpfMonitor(history, Outputs())
        m.update(readings(1.0, 24.0, 100_000.0), 0, 0)
        m.update(readings(1.0, 3.0, 100_010.0), 600_000, 600_000)
        m.onSessionEnd(601_000, 601_000)
        DpfMonitor(history, Outputs()).update(readings(0.0, 3.0, 100_010.0), 2_000_000, 1_000)
        assertEquals(RegenRecord.Outcome.COMPLETED, history.load().single().outcome)
    }

    @Test
    fun `without a status PID nothing happens`() {
        val out = Outputs()
        val m = DpfMonitor(history, out)
        m.update(mapOf(PidRole.SOOT to 24.0), 0, 0)
        assertFalse(m.isActive)
        assertTrue(history.load().isEmpty())
    }

    @Test
    fun `silent when voice is off`() {
        val out = Outputs()
        val m = DpfMonitor(history, out, voice = false)
        m.update(readings(1.0, 24.0, 100_000.0), 0, 0)
        m.update(readings(0.0, 2.0, 100_010.0), 600_000, 600_000)
        assertTrue(out.spoken.isEmpty())
    }

    @Test
    fun `roles are found by name in the SKYACTIV-D PID pack`() {
        val pids = listOf(
            "22f48b,115616640" to "DPF Regeneration Status",
            "22f48b,1301210893" to "DPF Regeneration Trigger",
            "22f48b,-1049019157" to "DPF Regeneration Distance Average (EWMA)",
            "22042c,-1485224063" to "DPF PM Accumulation",
            "22044b,5" to "DPF PM Accumulation Desired",
            "220432,-1191570271" to "DPF Regeneration Count",
            "220434,25467280" to "DPF Regeneration Distance",
            "22dd01,7" to "Total Distance",
            "0c,0" to "Engine RPM"
        )
        val roles = PidRoles.resolve(pids, emptyMap())
        assertEquals("22f48b,115616640", roles[PidRole.REGEN_STATUS])
        assertEquals("22f48b,1301210893", roles[PidRole.REGEN_TRIGGER])
        assertEquals("22042c,-1485224063", roles[PidRole.SOOT])
        assertEquals("22044b,5", roles[PidRole.SOOT_LIMIT])
        assertEquals("220432,-1191570271", roles[PidRole.REGEN_COUNT])
        assertEquals("220434,25467280", roles[PidRole.DISTANCE_SINCE_REGEN])
        assertEquals("22dd01,7", roles[PidRole.TOTAL_DISTANCE])
        assertEquals("0d,0", roles[PidRole.SPEED])

        val manual = PidRoles.resolve(pids, mapOf(PidRole.REGEN_STATUS to "22f48b,999"))
        assertEquals("22f48b,999", manual[PidRole.REGEN_STATUS])
    }
}
