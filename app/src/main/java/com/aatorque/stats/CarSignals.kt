package com.aatorque.stats

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.support.car.CarInfoManager
import android.support.car.hardware.CarSensorEvent
import android.support.car.hardware.CarSensorManager
import timber.log.Timber

/**
 * The car's own signals through the Android Auto SDK (CarSensorManager), in log-only mode.
 *
 * Nobody has published which sensors a given head unit sends (Mazda only says "location, speed
 * and other vehicle data"). Before any feature relies on them, this logs:
 * - which sensors the car offers;
 * - the connection type (USB or Wi-Fi);
 * - car and head-unit info;
 * - a sample of each sensor's values.
 *
 * Nothing in the app reacts to these values yet.
 *
 * The SDK loads its implementation from the Android Auto app at run time, so an Android Auto
 * update can make it throw anything, LinkageError included. Everything runs on its own thread
 * and every call is wrapped, so a failure here never reaches the dashboard.
 *
 * Privacy: location values are never logged, only whether the sensor exists. The vehicle id is
 * not logged.
 */
object CarSignals {
    private const val LOG_EVERY_MS = 15_000L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var car: Car? = null
    private var sensors: CarSensorManager? = null
    private val lastLogged = HashMap<Int, Long>()
    private var lastDrivingStatus: Int? = null

    private val names: Map<Int, String> = mapOf(
        CarSensorManager.SENSOR_TYPE_COMPASS to "COMPASS",
        CarSensorManager.SENSOR_TYPE_CAR_SPEED to "CAR_SPEED",
        CarSensorManager.SENSOR_TYPE_RPM to "RPM",
        CarSensorManager.SENSOR_TYPE_ODOMETER to "ODOMETER",
        CarSensorManager.SENSOR_TYPE_FUEL_LEVEL to "FUEL_LEVEL",
        CarSensorManager.SENSOR_TYPE_PARKING_BRAKE to "PARKING_BRAKE",
        CarSensorManager.SENSOR_TYPE_GEAR to "GEAR",
        CarSensorManager.SENSOR_TYPE_NIGHT to "NIGHT",
        CarSensorManager.SENSOR_TYPE_LOCATION to "LOCATION",
        CarSensorManager.SENSOR_TYPE_DRIVING_STATUS to "DRIVING_STATUS",
        CarSensorManager.SENSOR_TYPE_ENVIRONMENT to "ENVIRONMENT",
        CarSensorManager.SENSOR_TYPE_ACCELEROMETER to "ACCELEROMETER",
        CarSensorManager.SENSOR_TYPE_GPS_SATELLITE to "GPS_SATELLITE",
        CarSensorManager.SENSOR_TYPE_GYROSCOPE to "GYROSCOPE",
        CarSensorManager.SENSOR_TYPE_WHEEL_TICK_DISTANCE to "WHEEL_TICK_DISTANCE",
        CarSensorManager.SENSOR_TYPE_ABS_ACTIVE to "ABS_ACTIVE",
        CarSensorManager.SENSOR_TYPE_TRACTION_CONTROL_ACTIVE to "TRACTION_CONTROL_ACTIVE"
    )

    /** Sensors that are listened to (location only reported as present, never read). */
    private val listened = listOf(
        CarSensorManager.SENSOR_TYPE_DRIVING_STATUS,
        CarSensorManager.SENSOR_TYPE_CAR_SPEED,
        CarSensorManager.SENSOR_TYPE_GEAR,
        CarSensorManager.SENSOR_TYPE_PARKING_BRAKE,
        CarSensorManager.SENSOR_TYPE_NIGHT,
        CarSensorManager.SENSOR_TYPE_FUEL_LEVEL,
        CarSensorManager.SENSOR_TYPE_ODOMETER,
        CarSensorManager.SENSOR_TYPE_ENVIRONMENT,
        CarSensorManager.SENSOR_TYPE_RPM
    )

    private fun name(type: Int) = names[type] ?: "TYPE_$type"

    private val listener = CarSensorManager.OnSensorChangedListener { _, event ->
        try {
            event?.let(::logEvent)
        } catch (t: Throwable) {
            Timber.w("Signals: bad event: $t")
        }
    }

    private val callback = object : CarConnectionCallback() {
        override fun onConnected(connected: Car) {
            try {
                describe(connected)
            } catch (t: Throwable) {
                Timber.w(t, "Signals: could not read the car's sensors")
            }
        }

        override fun onDisconnected(disconnected: Car) {
            Timber.i("Signals: car disconnected")
            sensors = null
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (thread != null) return
        val app = context.applicationContext
        val t = HandlerThread("car-signals").apply {
            setUncaughtExceptionHandler { _, e -> Timber.w(e, "Signals: thread failed") }
            start()
        }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            try {
                car = Car.createCar(app, callback, h).also { it.connect() }
            } catch (e: Throwable) {
                Timber.w("Signals: Car.createCar failed: $e")
            }
        }
    }

    @Synchronized
    fun stop() {
        val h = handler ?: return
        h.post {
            try {
                sensors?.removeListener(listener)
            } catch (e: Throwable) {
                Timber.w("Signals: removeListener failed: $e")
            }
            try {
                car?.disconnect()
            } catch (e: Throwable) {
                Timber.w("Signals: disconnect failed: $e")
            }
            car = null
            sensors = null
            lastLogged.clear()
            lastDrivingStatus = null
            thread?.quitSafely()
        }
        thread = null
        handler = null
    }

    private fun describe(c: Car) {
        Timber.i("Signals: connection type ${safe { c.carConnectionType }} (1 USB, 2 Wi-Fi)")
        safe {
            val info = c.getCarManager(CarInfoManager::class.java)
            Timber.i(
                "Signals: car ${safe { info.manufacturer }} ${safe { info.model }} " +
                    "${safe { info.modelYear }}, head unit ${safe { info.headunitManufacturer }} " +
                    "${safe { info.headunitModel }} sw ${safe { info.headunitSoftwareVersion }}"
            )
        }
        val manager = c.getCarManager(CarSensorManager::class.java)
        sensors = manager
        val supported = manager.supportedSensors?.toList().orEmpty()
        Timber.i("Signals: supported sensors ${supported.joinToString { name(it) }}")
        for (type in listened) {
            if (type !in supported) continue
            try {
                manager.getLatestSensorEvent(type)?.let(::logEvent)
                val ok = manager.addListener(listener, type, CarSensorManager.SENSOR_RATE_NORMAL)
                Timber.i("Signals: listening to ${name(type)}: $ok")
            } catch (e: SecurityException) {
                Timber.w("Signals: ${name(type)} needs a permission: ${e.message}")
            } catch (e: Throwable) {
                Timber.w("Signals: ${name(type)} failed: $e")
            }
        }
    }

    /** One line per sensor every 15 s at most; every change of the driving status. */
    private fun logEvent(event: CarSensorEvent) {
        val type = event.sensorType
        if (type == CarSensorManager.SENSOR_TYPE_LOCATION) return
        if (type == CarSensorManager.SENSOR_TYPE_DRIVING_STATUS) {
            val status = event.drivingStatusData.status
            if (status != lastDrivingStatus) {
                lastDrivingStatus = status
                Timber.i("Signals: DRIVING_STATUS $status (0 = unrestricted/parked)")
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastLogged[type]
        if (last != null && now - last < LOG_EVERY_MS) return
        lastLogged[type] = now
        Timber.i("Signals: ${name(type)} $event")
    }

    private inline fun <T> safe(block: () -> T): String = try {
        block().toString()
    } catch (e: Throwable) {
        "?"
    }
}
