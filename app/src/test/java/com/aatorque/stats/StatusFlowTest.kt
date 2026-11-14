package com.aatorque.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aatorque.datastore.Display
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The dashboard calls makeExecutors before its gauges are loaded and again on every settings
 * change. That must not hide a bind problem behind "Set up gauges", and must not leave
 * callbacks on the shared connection after the dashboard is gone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatusFlowTest {

    private fun addGauge(refresher: TorqueRefresher) {
        val display = Display.newBuilder()
            .setPid("torque_0c")
            .setMinValue(0)
            .setMaxValue(100)
            .build()
        refresher.data[0] = TorqueData(display)
    }

    @Test
    fun `a missing Torque survives the empty first pass`() = runBlocking {
        val refresher = TorqueRefresher()
        val service = TorqueService()
        refresher.reportTorqueMissing()

        refresher.makeExecutors(service)
        assertEquals(ConnectStatus.SETUP_GAUGE, refresher.connectStatus.first())

        addGauge(refresher)
        refresher.makeExecutors(service)
        assertEquals(ConnectStatus.NO_TORQUE, refresher.connectStatus.first())
        refresher.shutdown(service)
    }

    @Test
    fun `without a bind problem the state goes back to connecting`() = runBlocking {
        val refresher = TorqueRefresher()
        val service = TorqueService()
        refresher.makeExecutors(service)
        addGauge(refresher)
        refresher.makeExecutors(service)
        assertEquals(ConnectStatus.CONNECTING_TORQUE, refresher.connectStatus.first())
        refresher.shutdown(service)
    }

    @Test
    fun `a destroyed dashboard leaves nothing on the shared connection`() = runBlocking {
        val refresher = TorqueRefresher()
        val service = TorqueService()
        addGauge(refresher)
        repeat(5) { refresher.makeExecutors(service) }
        assertEquals(1, service.pendingCallbackCount())

        refresher.shutdown(service)
        assertEquals(0, service.pendingCallbackCount())
    }
}
