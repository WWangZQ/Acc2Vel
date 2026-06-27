package com.av.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Watch velocity estimator — robust approach for wrist-worn IMU.
 *
 * Key insight: don't try to do full 3D gravity removal (orientation errors
 * cause gravity leak → velocity explodes). Instead:
 *
 * 1. Compute |accel| magnitude — orientation independent
 * 2. Subtract g: net_accel = |a| - 9.81
 * 3. This gives TRUE scalar acceleration regardless of watch orientation
 * 4. Low-pass filter to remove arm swing / vibration noise
 * 5. ZUPT: reset velocity when signal is quiet
 * 6. Integrate filtered acceleration → velocity
 *
 * Walking produces oscillating |a| around 9.81 (±0.5 m/s²).
 * Standing still: |a| ≈ 9.81, very low variance.
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
    private var calibrating = false
    private var calibrationDone = false
    private var calibrationSamples = mutableListOf<Float>()  // |a| values
    private val CALIBRATION_COUNT = 200

    // Sensor data
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var sampleCount = 0L
    private var lastTimestampNs = 0L
    private val recentIntervals = ArrayDeque<Long>(100)

    // Velocity estimation
    private var velocity = 0f           // m/s (scalar, always ≥ 0)
    private var gravity = 9.80665f      // calibrated during init
    private var netAccelFiltered = 0f   // low-pass filtered net acceleration

    // Low-pass filter state (for net acceleration)
    private val LPF_ALPHA = 0.08f       // lower = smoother, slower response

    // ZUPT detection (using magnitude variance)
    private val magnitudeBuffer = ArrayDeque<Float>(50)
    private var isStationary = false
    private var stationaryCount = 0

    // Gyro magnitude (for vibration detection)
    private var gyroMagnitude = 0f

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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Acc2Vel:Main")
        wakeLock.acquire(60 * 60 * 1000L)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
        }

        statusText = TextView(this).apply {
            text = "Ready — tap Start"
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
            text = "Start Tracking"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { toggle() }
        }
        layout.addView(startButton)

        scroll.addView(layout)
        setContentView(scroll)

        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
    }

    private fun toggle() {
        if (!isTracking) startCalibration() else stopTracking()
    }

    private fun startCalibration() {
        isTracking = true
        calibrating = true
        calibrationDone = false
        calibrationSamples.clear()
        sampleCount = 0
        lastTimestampNs = 0
        recentIntervals.clear()
        velocity = 0f
        netAccelFiltered = 0f
        magnitudeBuffer.clear()
        isStationary = false
        stationaryCount = 0

        startButton.text = "Calibrating..."
        startButton.isEnabled = false
        statusText.text = "Hold still — calibrating..."

        val rate = SensorManager.SENSOR_DELAY_FASTEST
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, rate)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, rate)
        }

        handler.post(uiUpdateRunnable)
    }

    private fun finishCalibration() {
        // Average |a| during stationary period → true gravity on this device
        gravity = if (calibrationSamples.isNotEmpty()) {
            calibrationSamples.average().toFloat()
        } else {
            9.80665f  // fallback
        }
        // Sanity check: gravity should be 8-11 m/s² on Earth
        if (gravity.isNaN() || gravity < 8f || gravity > 11f) gravity = 9.80665f

        calibrationSamples.clear()
        calibrating = false
        calibrationDone = true
        startButton.text = "Stop"
        startButton.isEnabled = true
        statusText.text = "Tracking — g=${"%.3f".format(gravity)}"
    }

    private fun stopTracking() {
        isTracking = false
        calibrating = false
        calibrationDone = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        startButton.text = "Start Tracking"
        statusText.text = "Stopped — max %.1f km/h".format(velocity * 3.6f)
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

        // Compute |a| magnitude — orientation independent!
        val aMag = sqrt(
            lastAccel[0] * lastAccel[0] +
            lastAccel[1] * lastAccel[1] +
            lastAccel[2] * lastAccel[2]
        )

        if (calibrating) {
            calibrationSamples.add(aMag)
            if (calibrationSamples.size >= CALIBRATION_COUNT) {
                handler.post { finishCalibration() }
            }
            return
        }

        if (!calibrationDone) return

        // Net acceleration: how much faster/slower than gravity
        // Positive = accelerating, negative = decelerating
        val netAccel = aMag - gravity

        // Low-pass filter the net acceleration
        // This removes arm swing, vibration, and high-freq noise
        netAccelFiltered = netAccelFiltered + LPF_ALPHA * (netAccel - netAccelFiltered)

        // ZUPT detection: is the signal quiet?
        magnitudeBuffer.addLast(aMag)
        if (magnitudeBuffer.size > 50) magnitudeBuffer.removeFirst()

        val variance = if (magnitudeBuffer.size >= 30) {
            val mean = magnitudeBuffer.average().toFloat()
            magnitudeBuffer.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / magnitudeBuffer.size
        } else 999f

        // Also check gyro — high rotation = vibration, not a real stop
        val gyroQuiet = gyroMagnitude < 0.3f  // rad/s

        isStationary = variance < 0.02f && gyroQuiet

        if (isStationary) {
            stationaryCount++
            // After 5 consecutive stationary readings, declare ZUPT
            if (stationaryCount >= 5) {
                velocity = 0f
                netAccelFiltered = 0f
            }
        } else {
            stationaryCount = 0

            // Compute dt
            val dt = if (recentIntervals.isNotEmpty()) {
                recentIntervals.last() / 1_000_000_000f
            } else 0.01f

            // Integrate filtered acceleration
            // Only integrate when gyro is relatively quiet (not spinning/shaking)
            // During high gyro activity, the |a| approach is unreliable
            val trustFactor = if (gyroMagnitude < 2.0f) 1f else 0.3f
            velocity += netAccelFiltered * dt * trustFactor

            // NaN safety
            if (velocity.isNaN()) velocity = 0f
            if (netAccelFiltered.isNaN()) netAccelFiltered = 0f

            // Clamp to reasonable range (0 - 120 km/h = 33 m/s)
            velocity = velocity.coerceIn(0f, 33f)
        }
    }

    private fun handleGyro(event: SensorEvent) {
        lastGyro = event.values.copyOf()
        gyroMagnitude = sqrt(
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

        if (calibrating) {
            statusText.text = "Calibrating... ${calibrationSamples.size}/$CALIBRATION_COUNT"
            val aMag = if (calibrationSamples.isNotEmpty()) calibrationSamples.last() else 0f
            speedText.text = "%.1f Hz".format(rateHz)
            detailText.text = "|a|=%.3f g=%.3f".format(aMag, calibrationSamples.averageOrNull() ?: 0f)
            return
        }

        val aMag = sqrt(
            lastAccel[0] * lastAccel[0] +
            lastAccel[1] * lastAccel[1] +
            lastAccel[2] * lastAccel[2]
        )

        statusText.text = if (isStationary) "STOPPED" else "TRACKING"
        speedText.text = "%.1f km/h".format(speedKmh)

        val sb = StringBuilder()
        sb.appendLine("|a|=${"%.3f".format(aMag)} g=${"%.3f".format(gravity)}")
        sb.appendLine("net=${"%+.4f".format(aMag - gravity)} filt=${"%+.4f".format(netAccelFiltered)}")
        sb.appendLine("gyro=${"%.2f".format(gyroMagnitude)} rad/s  ${"%.0f".format(rateHz)}Hz")
        sb.appendLine("v=${"%.2f".format(velocity)} m/s  n=$sampleCount")
        detailText.text = sb.toString()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}

// Extension for nullable average
private fun Collection<Float>.averageOrNull(): Float? {
    return if (isEmpty()) null else average().toFloat()
}
