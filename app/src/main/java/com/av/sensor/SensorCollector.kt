package com.av.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.av.data.GpsFix
import com.av.data.ImuSample
import com.av.data.SensorInfo
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android SensorManager and FusedLocationProviderClient to stream
 * IMU samples and GPS fixes as coroutine Flows.
 *
 * Accelerometer, gyroscope, magnetometer, and barometer are fused into
 * a single [ImuSample] per accelerometer tick (fastest sensor).
 * GPS fixes are emitted independently on their own cadence.
 */
@Singleton
class SensorCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensor references
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    companion object {
        private const val TAG = "SensorCollector"
    }

    /**
     * Query what sensors are available and their specs.
     */
    fun getSensorInfo(): SensorInfo {
        return SensorInfo(
            hasAccelerometer = accelSensor != null,
            hasGyroscope = gyroSensor != null,
            hasMagnetometer = magSensor != null,
            hasBarometer = pressureSensor != null,
            hasGps = true,  // assume GPS is available; will fail gracefully if not
            accelMaxRange = accelSensor?.maximumRange ?: 0f,
            gyroMaxRange = gyroSensor?.maximumRange ?: 0f,
            accelResolution = accelSensor?.resolution ?: 0f,
            gyroResolution = gyroSensor?.resolution ?: 0f,
            maxSamplingRateHz = estimateMaxRate()
        )
    }

    private fun estimateMaxRate(): Int {
        // SENSOR_DELAY_FASTEST is typically 200-500 Hz depending on hardware
        return accelSensor?.let { sensor ->
            val minDelayUs = sensor.minDelay
            if (minDelayUs > 0) 1_000_000 / minDelayUs else 200
        } ?: 0
    }

    /**
     * Stream fused IMU samples. Accelerometer acts as the "clock" — each
     * accel tick produces one ImuSample with the latest gyro/mag/pressure
     * values merged in.
     */
    fun imuFlow(): Flow<ImuSample> = callbackFlow {
        // Buffers for latest non-accel readings (merged on accel tick)
        @Volatile var latestGyro: FloatArray? = null
        @Volatile var latestMag: FloatArray? = null
        @Volatile var latestPressure: Float? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val gyro = latestGyro ?: floatArrayOf(0f, 0f, 0f)
                        val sample = ImuSample(
                            timestampNs = event.timestamp,
                            accel = floatArrayOf(event.values[0], event.values[1], event.values[2]),
                            gyro = gyro.copyOf(),
                            mag = latestMag?.copyOf(),
                            pressure = latestPressure
                        )
                        trySend(sample)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        latestGyro = floatArrayOf(event.values[0], event.values[1], event.values[2])
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        latestMag = floatArrayOf(event.values[0], event.values[1], event.values[2])
                    }
                    Sensor.TYPE_PRESSURE -> {
                        latestPressure = event.values[0]
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                Log.d(TAG, "Accuracy changed: ${sensor.name} → $accuracy")
            }
        }

        // Register all sensors at fastest rate
        val rate = SensorManager.SENSOR_DELAY_FASTEST
        accelSensor?.let { sensorManager.registerListener(listener, it, rate) }
        gyroSensor?.let { sensorManager.registerListener(listener, it, rate) }
        magSensor?.let { sensorManager.registerListener(listener, it, rate) }
        pressureSensor?.let { sensorManager.registerListener(listener, it, rate) }

        if (accelSensor == null) {
            close(IllegalStateException("No accelerometer available on this device"))
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "IMU listener unregistered")
        }
    }

    /**
     * Stream GPS fixes. Uses FusedLocationProvider with high accuracy.
     * Interval: 1 second (fast enough for velocity ground-truth).
     */
    fun gpsFlow(): Flow<GpsFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    val fix = GpsFix(
                        timestampNs = SystemClock.elapsedRealtimeNanos(),
                        lat = location.latitude,
                        lon = location.longitude,
                        speed = location.speed,
                        bearing = location.bearing,
                        accuracy = location.accuracy
                    )
                    trySend(fix)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, null)
        } catch (e: SecurityException) {
            close(e)
        }

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
            Log.d(TAG, "GPS listener removed")
        }
    }
}
