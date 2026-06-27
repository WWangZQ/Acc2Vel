package com.av.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.av.data.GpsFix
import com.av.data.ImuSample
import com.av.data.SensorInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps Android SensorManager and LocationManager to stream
 * IMU samples and GPS fixes as coroutine Flows.
 *
 * Uses standard Android LocationManager (no Google Play Services dependency).
 * This is compatible with Chinese-market watches that lack GMS.
 */
class SensorCollector(
    private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

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
        // Check all available sensors for logging
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        Log.i(TAG, "=== All available sensors (${allSensors.size}) ===")
        for (s in allSensors) {
            Log.i(TAG, "  [${s.type}] ${s.name} (${s.vendor}) — range=${s.maximumRange}, res=${s.resolution}, minDelay=${s.minDelay}µs")
        }
        Log.i(TAG, "=== End sensor list ===")

        return SensorInfo(
            hasAccelerometer = accelSensor != null,
            hasGyroscope = gyroSensor != null,
            hasMagnetometer = magSensor != null,
            hasBarometer = pressureSensor != null,
            hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER),
            accelMaxRange = accelSensor?.maximumRange ?: 0f,
            gyroMaxRange = gyroSensor?.maximumRange ?: 0f,
            accelResolution = accelSensor?.resolution ?: 0f,
            gyroResolution = gyroSensor?.resolution ?: 0f,
            maxSamplingRateHz = estimateMaxRate()
        )
    }

    private fun estimateMaxRate(): Int {
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
     * Stream GPS fixes using standard LocationManager (no Google Play Services).
     */
    fun gpsFlow(): Flow<GpsFix> = callbackFlow {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            close(SecurityException("Location permission not granted"))
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = GpsFix(
                    timestampNs = android.os.SystemClock.elapsedRealtimeNanos(),
                    lat = location.latitude,
                    lon = location.longitude,
                    speed = location.speed,
                    bearing = location.bearing,
                    accuracy = location.accuracy
                )
                trySend(fix)
            }

            @Deprecated("Deprecated in API")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // Try GPS provider first
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // min time ms
                    0f,    // min distance meters
                    listener,
                    Looper.getMainLooper()
                )
            }
            // Also try network provider as fallback
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L, 0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            close(e)
        }

        awaitClose {
            locationManager.removeUpdates(listener)
            Log.d(TAG, "GPS listener removed")
        }
    }
}
