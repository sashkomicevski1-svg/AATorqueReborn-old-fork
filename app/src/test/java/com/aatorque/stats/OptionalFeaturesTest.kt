package com.aatorque.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aatorque.datastore.Coloring
import com.aatorque.datastore.Display
import com.aatorque.datastore.Screen
import com.aatorque.datastore.UserPreference
import com.aatorque.prefs.ProfileStore
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
class OptionalFeaturesTest {

    // --- Acceleration timer -------------------------------------------------------------

    private fun timer(): Pair<AccelTimer, MutableList<Pair<AccelTimer.Kind, Double>>> {
        val results = mutableListOf<Pair<AccelTimer.Kind, Double>>()
        return AccelTimer { kind, s -> results.add(kind to s) } to results
    }

    @Test
    fun `0-100 and 60-100 from a steady pull`() {
        val (t, results) = timer()
        // Standing, then +10 km/h per second, read every 250 ms.
        t.feed(0.0, 0)
        t.feed(0.0, 500)
        var time = 500L
        var speed = 0.0
        while (speed < 110) {
            time += 250
            speed += 2.5
            t.feed(speed, time)
        }
        val zero = results.single { it.first == AccelTimer.Kind.ZERO_100 }.second
        val sixty = results.single { it.first == AccelTimer.Kind.SIXTY_100 }.second
        // Leaves 0.5 km/h at 0.55 s, reaches 100 at 10.5 s: 9.95 s.
        assertEquals(9.95, zero, 0.01)
        assertEquals(4.0, sixty, 0.01)
    }

    @Test
    fun `a run is dropped when the driver brakes`() {
        val (t, results) = timer()
        t.feed(0.0, 0)
        t.feed(20.0, 1_000)
        t.feed(40.0, 2_000)
        t.feed(30.0, 3_000)
        t.feed(100.0, 6_000)
        assertTrue(results.none { it.first == AccelTimer.Kind.ZERO_100 })
    }

    @Test
    fun `a slow run times out`() {
        val (t, results) = timer()
        t.feed(0.0, 0)
        t.feed(10.0, 1_000)
        t.feed(99.0, 41_000)
        t.feed(101.0, 42_000)
        assertTrue(results.none { it.first == AccelTimer.Kind.ZERO_100 })
    }

    @Test
    fun `no 0-100 without a standstill first`() {
        val (t, results) = timer()
        listOf(30.0, 50.0, 70.0, 90.0, 100.0).forEachIndexed { i, s -> t.feed(s, i * 1_000L) }
        assertEquals(listOf(AccelTimer.Kind.SIXTY_100), results.map { it.first })
        assertEquals(2.5, results.single().second, 0.01)
    }

    @Test
    fun `creeping and stopping restarts the 0-100 run`() {
        val (t, results) = timer()
        t.feed(0.0, 0)
        t.feed(2.0, 1_000)
        t.feed(0.0, 2_000)
        // Wait at the light for 12 s (readings keep coming), then a 9 s pull.
        var time = 2_000L
        repeat(48) {
            time += 250
            t.feed(0.0, time)
        }
        var speed = 0.0
        while (speed < 100) {
            time += 250
            speed += 100.0 / 36
            t.feed(speed, time)
        }
        val zero = results.single { it.first == AccelTimer.Kind.ZERO_100 }.second
        assertTrue("got $zero", zero < 10.0)
    }

    @Test
    fun `holding a speed past 60 drops the 60-100 run`() {
        val (t, results) = timer()
        t.feed(58.0, 0)
        t.feed(61.0, 500)
        var time = 500L
        repeat(40) {
            time += 250
            t.feed(61.0, time)
        }
        var speed = 61.0
        while (speed < 100) {
            time += 250
            speed += 1.25
            t.feed(speed, time)
        }
        assertTrue(results.none { it.first == AccelTimer.Kind.SIXTY_100 })
    }

    @Test
    fun `a gap between readings drops the run`() {
        val (t, results) = timer()
        t.feed(0.0, 0)
        t.feed(10.0, 500)
        t.feed(50.0, 1_000)
        t.feed(100.0, 5_000)
        assertTrue(results.isEmpty())
    }

    // --- Night theme --------------------------------------------------------------------

    private fun prefs(night: String, clock: Boolean) = UserPreference.newBuilder()
        .setSelectedTheme("Minimalistic")
        .setNightTheme(night)
        .setNightBySchedule(clock)
        .build()

    @Test
    fun `night theme follows the car or the clock`() {
        val car = prefs("Mazda Style", clock = false)
        assertEquals("Mazda Style", NightTheme.effective(car, NightTheme.isNight(car, true, 12)))
        assertEquals("Minimalistic", NightTheme.effective(car, NightTheme.isNight(car, false, 23)))

        val clock = prefs("Mazda Style", clock = true)
        assertEquals(
            "Mazda Style",
            NightTheme.effective(clock, NightTheme.isNight(clock, false, 21))
        )
        assertEquals(
            "Mazda Style",
            NightTheme.effective(clock, NightTheme.isNight(clock, false, 5))
        )
        assertEquals(
            "Minimalistic",
            NightTheme.effective(clock, NightTheme.isNight(clock, true, 12))
        )

        val off = prefs("", clock = false)
        assertEquals("Minimalistic", NightTheme.effective(off, true))
    }

    // --- Profiles -----------------------------------------------------------------------

    @Test
    fun `profiles save, list, load and delete`() {
        val dir = Files.createTempDirectory("profiles").toFile()
        val store = ProfileStore(dir)
        val setup = UserPreference.newBuilder()
            .setSelectedTheme("Mazda Style")
            .addScreens(Screen.newBuilder().setTitle("DPF"))
            .build()

        assertEquals("Mazda 6", store.save("  Mazda 6 ", setup))
        assertEquals("Wifecar", store.save("Wife/car", setup))
        assertNull(store.save("///", setup))
        assertEquals(listOf("Mazda 6", "Wifecar"), store.list())
        assertEquals(setup, store.load("Mazda 6"))
        assertTrue(store.delete("Wifecar"))
        assertEquals(listOf("Mazda 6"), store.list())
        assertNull(store.load("missing"))
    }

    @Test
    fun `a name cannot leave the profile folder`() {
        assertEquals("..etc", ProfileStore.clean("../etc"))
        assertFalse(ProfileStore.clean("a/b\\c").contains('/'))
    }

    // --- Backup compatibility -----------------------------------------------------------

    @Test
    fun `older backups read the new fields as off`() {
        val old = Display.newBuilder()
            .setPid("torque_0c,0")
            .addAlarms(Coloring.newBuilder().setValue(1.0))
            .build()
        val parsed = Display.parseFrom(old.toByteArray())
        assertFalse(parsed.digital)
        assertFalse(parsed.getAlarms(0).blink)
        val prefs = UserPreference.parseFrom(UserPreference.getDefaultInstance().toByteArray())
        assertEquals("", prefs.nightTheme)
        assertFalse(prefs.nightBySchedule)
    }
}
