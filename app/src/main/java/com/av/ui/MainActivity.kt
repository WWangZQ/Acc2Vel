package com.av.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.widget.TextView
import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.av.data.ImuSample
import com.av.data.NavState
import com.av.ekf.ExtendedKalmanFilter

/**
 * Watch-optimized main activity.
 * No Compose, no Service — everything runs directly in the Activity.
 * Minimal UI for small round watch screen.
 */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private val ekf = ExtendedKalmanFilter()

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var detailText: TextView
    private lateinit var startButton: Button

    // State
    private var isCalibrating = false
    private var isTracking = false
    private var calibrationSamples = mutableListOf<ImuSample>()
    private val CALIBRATION_COUNT = 200  // ~2 seconds at 100Hz

    private var latestAccel = floatArrayOf(0f, 0f, 0f)
    private var latestGyro = floatArrayOf(0f, 0f, 0f)
    private var sampleCount = 0L
    private var firstTimestampNs = 0L
    private var lastTimestampNs = 0L
    private val recentIntervals = ArrayDeque<Long>(100)

    private val handler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Acc2Vel:Main")
        wakeLock.acquire(60 * 60 * 1000L)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Build UI
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
        }

        // Status
        statusText = TextView(this).apply {
            text = "Ready — tap Start"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        layout.addView(statusText)

        // Speed display (big number)
        speedText = TextView(this).apply {
            text = "0.0 km/h"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        }
        layout.addView(speedText)

        // Detail text
        detailText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        layout.addView(detailText)

        // Start/Stop button
        startButton = Button(this).apply {
            text = "Start Tracking"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onButtonClicked() }
        }
        layout.addView(startButton)

        scroll.addView(layout)
        setContentView(scroll)

        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
    }

    private fun onButtonClicked() {
        if (!isTracking && !isCalibrating) {
            startCalibration()
        } else if (isTracking) {
            stopTracking()
        }
    }

    private fun startCalibration() {
        isCalibrating = true
        isTracking = false
        calibrationSamples.clear()
        sampleCount = 0
        firstTimestampNs = 0
        lastTimestampNs = 0
        recentIntervals.clear()
        ekf.reset()
        startButton.text = "Calibrating..."
        startButton.isEnabled = false
        statusText.text = "Hold still — calibrating..."

        // Start sensors
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
        val accelMean = floatArrayOf(0f, 0f, 0f)
        val gyroMean = floatArrayOf(0f, 0f, 0f)
        for (s in calibrationSamples) {
            accelMean[0] += s.accel[0]; accelMean[1] += s.accel[1]; accelMean[2] += s.accel[2]
            gyroMean[0] += s.gyro[0]; gyroMean[1] += s.gyro[1]; gyroMean[2] += s.gyro[2]
        }
        val n = calibrationSamples.size.toFloat()
        accelMean[0] /= n; accelMean[1] /= n; accelMean[2] /= n
        gyroMean[0] /= n; gyroMean[1] /= n; gyroMean[2] /= n

        ekf.initialize(accelMean, gyroMean)
        calibrationSamples.clear()
        isCalibrating = false
        isTracking = true
        startButton.text = "Stop"
        startButton.isEnabled = true
        statusText.text = "Tracking"
    }

    private fun stopTracking() {
        isTracking = false
        isCalibrating = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        startButton.text = "Start Tracking"
        statusText.text = "Stopped"
        speedText.text = "%.1f km/h".format(ekf.getState().speedKmh)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = event.values.copyOf()
                sampleCount++

                // Rate measurement
                val ts = event.timestamp
                if (firstTimestampNs == 0L) firstTimestampNs = ts
                else if (lastTimestampNs > 0) {
                    val interval = ts - lastTimestampNs
                    if (interval in 1..100_000_000) {
                        recentIntervals.addLast(interval)
                        if (recentIntervals.size > 100) recentIntervals.removeFirst()
                    }
                }
                lastTimestampNs = ts

                // Build IMU sample
                val sample = ImuSample(
                    timestampNs = ts,
                    accel = latestAccel.copyOf(),
                    gyro = latestGyro.copyOf()
                )

                if (isCalibrating) {
                    calibrationSamples.add(sample)
                    if (calibrationSamples.size >= CALIBRATION_COUNT) {
                        handler.post { finishCalibration() }
                    }
                } else if (isTracking) {
                    // Run EKF pipeline
                    ekf.predict(sample)
                    val isZupt = ekf.getZuptDetector().update(sample)
                    if (isZupt) ekf.updateZupt()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = event.values.copyOf()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updateUI() {
        if (!isTracking && !isCalibrating) return

        // Rate
        val rateHz = if (recentIntervals.isNotEmpty()) {
            1_000_000_000.0 / recentIntervals.average()
        } else 0.0

        if (isCalibrating) {
            statusText.text = "Calibrating... ${calibrationSamples.size}/$CALIBRATION_COUNT"
            speedText.text = "%.1f Hz".format(rateHz)
            detailText.text = "a: ${"%+.2f".format(latestAccel[0])} ${"%+.2f".format(latestAccel[1])} ${"%+.2f".format(latestAccel[2])}"
            return
        }

        val state = ekf.getState()
        statusText.text = if (state.isZupt) "STOPPED" else "TRACKING · ${"%.0f".format(rateHz)}Hz"
        speedText.text = "%.1f km/h".format(state.speedKmh)

        val sb = StringBuilder()
        sb.appendLine("v: ${"%+.2f".format(state.velocity[0])} ${"%+.2f".format(state.velocity[1])} ${"%+.2f".format(state.velocity[2])} m/s")
        sb.appendLine("a: ${"%+.2f".format(latestAccel[0])} ${"%+.2f".format(latestAccel[1])} ${"%+.2f".format(latestAccel[2])}")
        sb.appendLine("bias: ${"%+.3f".format(state.accelBias[0])} ${"%+.3f".format(state.accelBias[1])} ${"%+.3f".format(state.accelBias[2])}")
        sb.appendLine("conf: ${"%.0f".format(state.confidence * 100)}%  n=$sampleCount")
        detailText.text = sb.toString()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
