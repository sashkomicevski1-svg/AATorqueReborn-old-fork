package com.aatorque.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DtcTest {

    @Test
    fun `decodes the code letters and digits`() {
        assertEquals("P0133", DtcParser.decode(0x01, 0x33))
        assertEquals("P2002", DtcParser.decode(0x20, 0x02))
        assertEquals("P252F", DtcParser.decode(0x25, 0x2F))
        assertEquals("C0300", DtcParser.decode(0x43, 0x00))
        assertEquals("B1234", DtcParser.decode(0x92, 0x34))
        assertEquals("U0100", DtcParser.decode(0xC1, 0x00))
    }

    @Test
    fun `CAN without headers`() {
        assertEquals(listOf("P0133", "P2002"), DtcParser.parse(arrayOf("43 02 01 33 20 02"), 3))
    }

    @Test
    fun `CAN with headers and several ECUs`() {
        val reply = arrayOf("7E8 04 43 01 20 02", "7E9 02 43 00")
        assertEquals(listOf("P2002"), DtcParser.parse(reply, 3))
    }

    @Test
    fun `multi-frame without headers`() {
        val reply = arrayOf("00A", "0: 43 04 01 33 20 02", "1: 24 63 25 2F 00 00")
        assertEquals(listOf("P0133", "P2002", "P2463", "P252F"), DtcParser.parse(reply, 3))
    }

    @Test
    fun `multi-frame with headers`() {
        val reply = arrayOf("7E8 10 0A 43 04 01 33 20 02", "7E8 21 24 63 25 2F 00 00 00")
        assertEquals(listOf("P0133", "P2002", "P2463", "P252F"), DtcParser.parse(reply, 3))
    }

    @Test
    fun `pending codes answer to mode 07`() {
        assertEquals(listOf("P0401"), DtcParser.parse(arrayOf("47 01 04 01"), 7))
    }

    @Test
    fun `no codes`() {
        assertTrue(DtcParser.parse(arrayOf("43 00"), 3).isEmpty())
        assertTrue(DtcParser.parse(arrayOf("NO DATA"), 3).isEmpty())
        assertTrue(DtcParser.parse(arrayOf("SEARCHING...", "NO DATA", ">"), 3).isEmpty())
        assertTrue(DtcParser.parse(null, 3).isEmpty())
        assertTrue(DtcParser.parse(arrayOf(), 3).isEmpty())
    }

    @Test
    fun `codes show once and are spoken once per session`() {
        val dir = Files.createTempDirectory("dtc").toFile()
        val store = JsonHistory(File(dir, "d.json"), 1, DtcReading::toJson, DtcReading::fromJson)
        var shown: CarNotices.Notice? = null
        val spoken = mutableListOf<String>()
        val m = DtcMonitor(store, { shown = it }, { spoken.add(it) })

        m.apply(listOf(Dtc("P2002", false)), 1_000)
        m.apply(listOf(Dtc("P2002", false)), 2_000)
        m.apply(listOf(Dtc("P2002", false), Dtc("P0401", true)), 3_000)
        assertEquals(listOf("Fault codes: P2002", "Fault codes: P0401"), spoken)
        assertEquals(CarNotices.Level.WARNING, shown?.level)
        assertEquals(2, store.load().single().codes.size)

        m.apply(emptyList(), 4_000)
        assertNull(shown)
        assertEquals("DPF efficiency below threshold", Dtc("P2002", false).description)
    }
}
