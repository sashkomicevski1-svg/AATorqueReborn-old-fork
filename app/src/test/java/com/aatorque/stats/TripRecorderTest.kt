package com.aatorque.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TripRecorderTest {

    @Test
    fun `distance comes from the odometer when the car has one`() {
        val t = TripRecorder(0, 0)
        t.update(mapOf(PidRole.TOTAL_DISTANCE to 100_000.0, PidRole.SPEED to 0.0), 0)
        t.update(mapOf(PidRole.TOTAL_DISTANCE to 100_023.4, PidRole.SPEED to 50.0), 1_800_000)
        val trip = t.finish(1_860_000, emptyList(), emptyList())
        assertEquals(23.4, trip.distanceKm, 0.001)
        assertEquals(31L, trip.durationMs / 60_000)
    }

    @Test
    fun `without an odometer distance comes from speed over time`() {
        val t = TripRecorder(0, 0)
        // 72 km/h for 100 s in 2 s steps = 2 km.
        for (i in 0..50) t.update(mapOf(PidRole.SPEED to 72.0), i * 2_000L)
        val trip = t.finish(100_000, emptyList(), emptyList())
        assertEquals(2.0, trip.distanceKm, 0.001)
        assertEquals(100_000L, trip.movingMs)
        assertEquals(72.0, trip.averageSpeed!!, 0.001)
        assertEquals(72.0, trip.maxSpeed!!, 0.0)
    }

    @Test
    fun `a gap in readings is not counted as driving`() {
        val t = TripRecorder(0, 0)
        t.update(mapOf(PidRole.SPEED to 60.0), 0)
        // The phone locked for ten minutes: at most 5 s are bridged.
        t.update(mapOf(PidRole.SPEED to 60.0), 600_000)
        val trip = t.finish(600_000, emptyList(), emptyList())
        assertEquals(60.0 * 5_000 / 3_600_000.0, trip.distanceKm, 0.0001)
    }

    @Test
    fun `standing still is not moving time`() {
        val t = TripRecorder(0, 0)
        for (i in 0..10) {
            t.update(
                mapOf(PidRole.SPEED to 0.0, PidRole.COOLANT to 80.0 + i),
                i * 1_000L
            )
        }
        val trip = t.finish(10_000, emptyList(), emptyList())
        assertEquals(0L, trip.movingMs)
        assertNull(trip.averageSpeed)
        assertEquals(90.0, trip.maxCoolant!!, 0.0)
    }

    @Test
    fun `regenerations and fault codes travel with the trip`() {
        val regens = listOf(
            RegenRecord(startWall = 1, outcome = RegenRecord.Outcome.COMPLETED),
            RegenRecord(startWall = 2, outcome = RegenRecord.Outcome.PAUSED)
        )
        val trip = TripRecorder(0, 0).finish(1_000, regens, listOf("P2002"))
        assertEquals(2, trip.regenerations)
        assertEquals(1, trip.regenCompleted)
        val back = TripRecord.fromJson(trip.toJson())
        assertEquals(trip, back)
    }
}
