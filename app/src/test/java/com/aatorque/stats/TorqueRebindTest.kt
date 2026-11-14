package com.aatorque.stats

import android.app.Application
import android.content.ComponentName
import android.os.Binder
import android.os.DeadObjectException
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aatorque.datastore.Display
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.prowl.torque.remote.ITorqueService
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Torque can be killed under the car screen. These tests pin the recovery: a dead binding is
 * rebuilt after a backoff, the connection is always released, and the dashboard says Torque
 * went away instead of freezing on the last frame.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TorqueRebindTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val name = ComponentName(TorqueService.TORQUE_PACKAGE, TorqueService.TORQUE_SERVICE)

    @Before
    fun setUp() {
        shadowOf(app).setComponentNameAndServiceForBindService(name, Binder())
    }

    private fun idle(ms: Long = 0) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun `a dead binding is rebuilt after the backoff`() {
        val service = TorqueService()
        service.startTorque(app)
        idle()
        assertNotNull(service.torqueService)

        val connection = shadowOf(app).boundServiceConnections.last()
        connection.onBindingDied(name)
        assertNull(service.torqueService)
        assertEquals(0, service.rebindCount)

        idle(TorqueService.MIN_BACKOFF_MS)
        assertEquals(1, service.rebindCount)
        assertNotNull(service.torqueService)
        service.onDestroy()
    }

    @Test
    fun `a null binding is retried too`() {
        val service = TorqueService()
        service.startTorque(app)
        idle()
        shadowOf(app).boundServiceConnections.last().onNullBinding(name)

        idle(TorqueService.MIN_BACKOFF_MS)
        assertEquals(1, service.rebindCount)
        service.onDestroy()
    }

    @Test
    fun `the connection is released even if Torque never answered`() {
        val service = TorqueService()
        service.startTorque(app)
        // No idle: onServiceConnected has not run, so the interface is still null.
        assertNull(service.torqueService)

        service.onDestroy()
        idle()
        assertTrue(shadowOf(app).unboundServiceConnections.isNotEmpty())
    }

    @Test
    fun `no rebind after the owner let go`() {
        val service = TorqueService()
        service.startTorque(app)
        idle()
        val connection = shadowOf(app).boundServiceConnections.last()
        service.onDestroy()

        connection.onBindingDied(name)
        idle(TorqueService.MAX_BACKOFF_MS)
        assertEquals(0, service.rebindCount)
    }

    @Test
    fun `the backoff doubles up to thirty seconds`() {
        var delay = TorqueService.MIN_BACKOFF_MS
        val seen = mutableListOf(delay)
        repeat(6) {
            delay = TorqueService.nextBackoff(delay)
            seen.add(delay)
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), seen)
    }

    @Test
    fun `a dead Torque during a refresh shows as lost`() = runBlocking {
        var dead = false
        val fake = Proxy.newProxyInstance(
            ITorqueService::class.java.classLoader,
            arrayOf(ITorqueService::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)

                "hashCode" -> 0

                "toString" -> "FakeTorque"

                "isConnectedToECU" -> true

                "getPIDValuesAsDouble" -> if (dead) {
                    throw DeadObjectException()
                } else {
                    doubleArrayOf(
                        1.0
                    )
                }

                "getPIDUpdateTime" -> longArrayOf(1L)

                else -> throw UnsupportedOperationException(method.name)
            }
        } as ITorqueService
        val service = TorqueService().apply { torqueService = fake }
        val refresher = TorqueRefresher()
        val display = Display.newBuilder()
            .setPid("torque_0c")
            .setMinValue(0)
            .setMaxValue(100)
            .build()
        refresher.data[0] = TorqueData(display)

        refresher.refreshDashboard(service)
        idle()
        assertEquals(ConnectStatus.CONNECTED, refresher.connectStatus.first())

        dead = true
        refresher.refreshDashboard(service)
        idle()
        assertNull(service.torqueService)
        assertEquals(ConnectStatus.TORQUE_LOST, refresher.connectStatus.first())
        service.onDestroy()
    }
}
