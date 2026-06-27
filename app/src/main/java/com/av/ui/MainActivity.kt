package com.av.ui

import android.Manifest
import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sqrt

/**
 * Metro velocity estimator.
 *
 * Metro is the ideal case for |a|-g integration because:
 * 1. No foot-strike bias (unlike walking)
 * 2. Station stops every 1-3 min → ZUPT resets drift
 * 3. Smooth acceleration (train, not human limbs)
 * 4. Wrist is still in the train's reference frame
 *
 * The trick: recalibrate gravity during EVERY station stop.
 * This kills the cumulative bias that was the #1 problem before.
 *
 * Turn handling: gyro detects rotation → reduce integration trust.
 */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock

    // UI
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var detailText: TextView
    private lateinit var startButton: Button

    // State
    private var isTracking = false
    private var sampleCount = 0L
    private var lastTimestampNs = 0L
    private val recentIntervals = ArrayDeque<Long>(100)

    // Sensor data
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var gyroMag = 0f

    // Gravity calibration (recalibrated at every station stop)
    private var gravity = 9.80665f
    private var gravitySamples = ArrayDeque<Float>(500)  // rolling window for gravity

    // Velocity
    private var velocity = 0f
    private var maxVelocity = 0f
    private var netAccelFiltered = 0f

    // Stationary detection (for ZUPT + gravity recalibration)
    private val accelMagBuffer = ArrayDeque<Float>(100)  // ~1s at 100Hz
    private var stationaryCount = 0        // consecutive stationary readings
    private var isStationary = false
    private var wasStationary = false      // previous state (for edge detection)
    private var stationStops = 0           // count of detected station stops
    private var lastStationTimeNs = 0L

    // Tuning parameters (metro-optimized)
    private val LPF_ALPHA = 0.12f          // low-pass filter for net acceleration
    private val STATIONARY_VAR_THRESHOLD = 0.008f  // variance threshold for "stopped"
    private val GYRO_QUIET_THRESHOLD = 0.2f  // rad/s — not turning
    private val STATIONARY_MIN_COUNT = 50    // ~0.5s of quiet → declare stationary
    private val MAX_SPEED_MS = 30f           // 108 km/h max metro speed
    private val GYRO_TURN_THRESHOLD = 0.5f   // rad/s — during turns, reduce trust

    private val handler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Acc2Vel:Metro")
        wakeLock.acquire(60 * 60 * 1000L)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
        }

        statusText = TextView(this).apply {
            text = "Metro mode — tap Start"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        layout.addView(statusText)

        speedText = TextView(this).apply {
            text = "0.0 km/h"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        }
        layout.addView(speedText)

        detailText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        layout.addView(detailText)

        startButton = Button(this).apply {
            text = "Start"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { toggle() }
        }
        layout.addView(startButton)

        scroll.addView(layout)
        setContentView(scroll)

        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
    }

    private fun toggle() {
        if (!isTracking) startTracking() else stopTracking()
    }

    private fun startTracking() {
        isTracking = true
        sampleCount = 0
        lastTimestampNs = 0
        recentIntervals.clear()
        velocity = 0f
        maxVelocity = 0f
        netAccelFiltered = 0f
        gravitySamples.clear()
        accelMagBuffer.clear()
        stationaryCount = 0
        isStationary = true       // start assuming stationary
        wasStationary = true
        stationStops = 0
        lastStationTimeNs = 0L

        startButton.text = "Stop"
        statusText.text = "Calibrating gravity..."

        val rate = SensorManager.SENSOR_DELAY_FASTEST
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, rate)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, rate)
        }

        handler.post(uiUpdateRunnable)
    }

    private fun stopTracking() {
        isTracking = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        startButton.text = "Start"
        statusText.text = "Done — ${stationStops} stops, max ${"%.1f".format(maxVelocity * 3.6f)} km/h"
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event)
            Sensor.TYPE_GYROSCOPE -> handleGyro(event)
        }
    }

    private fun handleAccel(event: SensorEvent) {
        lastAccel = event.values.copyOf()
        sampleCount++

        val ts = event.timestamp
        if (lastTimestampNs > 0) {
            val interval = ts - lastTimestampNs
            if (interval in 1..100_000_000) {
                recentIntervals.addLast(interval)
                if (recentIntervals.size > 100) recentIntervals.removeFirst()
            }
        }
        lastTimestampNs = ts

        if (!isTracking) return

        val aMag = sqrt(
            lastAccel[0] * lastAccel[0] +
            lastAccel[1] * lastAccel[1] +
            lastAccel[2] * lastAccel[2]
        )

        // Always feed into gravity calibration window (we'll use it when stationary)
        gravitySamples.addLast(aMag)
        if (gravitySamples.size > 500) gravitySamples.removeFirst()

        // Stationary detection
        accelMagBuffer.addLast(aMag)
        if (accelMagBuffer.size > 100) accelMagBuffer.removeFirst()

        if (accelMagBuffer.size >= 50) {
            val mean = accelMagBuffer.average().toFloat()
            val variance = accelMagBuffer.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / accelMagBuffer.size
            val gyroQuiet = gyroMag < GYRO_QUIET_THRESHOLD

            isStationary = variance < STATIONARY_VAR_THRESHOLD && gyroQuiet
        }

        // === Station stop edge detection (transition: moving → stopped) ===
        if (isStationary && !wasStationary) {
            // Just became stationary → this is a station stop!
            stationaryCount++
            if (stationaryCount >= STATIONARY_MIN_COUNT) {
                onStationStop(ts)
            }
        } else if (isStationary) {
            stationaryCount++
            // Keep recalibrating gravity while stationary
            if (stationaryCount >= STATIONARY_MIN_COUNT && gravitySamples.size >= 30) {
                val recent = gravitySamples.toList().takeLast(30)
                val newG = recent.average().toFloat()
                if (newG in 8.5f..11f) {
                    gravity = newG
                }
            }
        } else {
            stationaryCount = 0
        }
        wasStationary = isStationary

        // === Velocity integration (only when moving) ===
        if (isStationary) {
            if (stationaryCount >= STATIONARY_MIN_COUNT) {
                velocity = 0f
                netAccelFiltered = 0f
            }
            return
        }

        // Net acceleration: |a| - calibrated g
        val netAccel = aMag - gravity

        // Low-pass filter
        netAccelFiltered += LPF_ALPHA * (netAccel - netAccelFiltered)

        // Compute dt
        val dt = if (recentIntervals.isNotEmpty()) {
            recentIntervals.last() / 1_000_000_000f
        } else 0.01f

        // Trust factor: reduce during turns (gyro active)
        val trustFactor = when {
            gyroMag > 1.5f -> 0.2f    // hard turn → very low trust
            gyroMag > GYRO_TURN_THRESHOLD -> 0.5f  // gentle turn → moderate trust
            else -> 1.0f               // straight → full trust
        }

        // Integrate
        velocity += netAccelFiltered * dt * trustFactor

        // NaN safety
        if (velocity.isNaN()) velocity = 0f

        // Clamp
        velocity = velocity.coerceIn(0f, MAX_SPEED_MS)
        if (velocity > maxVelocity) maxVelocity = velocity
    }

    private fun onStationStop(ts: Long) {
        stationStops++
        lastStationTimeNs = ts
        velocity = 0f
        netAccelFiltered = 0f

        // Recalibrate gravity from the stationary window
        if (gravitySamples.size >= 30) {
            val recent = gravitySamples.toList().takeLast(50)
            val newG = recent.average().toFloat()
            if (newG in 8.5f..11f) {
                gravity = newG
            }
        }
    }

    private fun handleGyro(event: SensorEvent) {
        lastGyro = event.values.copyOf()
        gyroMag = sqrt(
            lastGyro[0] * lastGyro[0] +
            lastGyro[1] * lastGyro[1] +
            lastGyro[2] * lastGyro[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updateUI() {
        if (!isTracking) return

        val rateHz = if (recentIntervals.isNotEmpty()) {
            1_000_000_000.0 / recentIntervals.average()
        } else 0.0

        val speedKmh = velocity * 3.6f
        val aMag = sqrt(
            lastAccel[0] * lastAccel[0] +
            lastAccel[1] * lastAccel[1] +
            lastAccel[2] * lastAccel[2]
        )

        statusText.text = when {
            isStationary && stationaryCount >= STATIONARY_MIN_COUNT -> "STOPPED ($stationStops stops)"
            isStationary -> "Stopping..."
            gyroMag > GYRO_TURN_THRESHOLD -> "TURNING"
            else -> "MOVING"
        }

        speedText.text = "%.1f km/h".format(speedKmh)

        val sb = StringBuilder()
        sb.appendLine("|a|=${"%.3f".format(aMag)} g=${"%.3f".format(gravity)}")
        sb.appendLine("net=${"%+.4f".format(aMag - gravity)} filt=${"%+.4f".format(netAccelFiltered)}")
        sb.appendLine("gyro=${"%.2f".format(gyroMag)} rad/s  ${"%.0f".format(rateHz)}Hz")
        sb.appendLine("v=${"%.2f".format(velocity)} m/s  max=${"%.1f".format(maxVelocity * 3.6f)}km/h")
        detailText.text = sb.toString()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
