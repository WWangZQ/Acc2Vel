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
 * Step-frequency based velocity estimator.
 *
 * Why not integrate acceleration?
 * - |a|-g has a positive bias during walking (impact peaks > deceleration dips)
 * - Centripetal acceleration during turns inflates |a|
 * - Low-pass filtered |a|-g drifts upward → velocity only grows
 *
 * Step-frequency approach:
 * - Each foot strike creates a sharp peak in |accel|
 * - Detect peaks → count steps → measure step interval
 * - Speed = stride_length × step_frequency
 * - stride_length ≈ 0.7m for normal walking
 * - During quiet (no steps): velocity = 0 (ZUPT)
 *
 * This is how commercial fitness trackers (Fitbit, Garmin, Apple Watch) work.
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

    // Sensor data
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var sampleCount = 0L
    private var lastTimestampNs = 0L
    private val recentIntervals = ArrayDeque<Long>(100)

    // Step detection
    private var lastAMag = 0f
    private var lastLastAMag = 0f
    private var lastPeakTimeNs = 0L
    private var stepCount = 0L

    // Step interval tracking (for speed calculation)
    private val stepIntervals = ArrayDeque<Long>(6)  // last 6 step intervals
    private val STRIDE_LENGTH = 0.7f  // meters per step (adjustable)

    // Peak detection state
    private var inPeak = false
    private var peakStartTimeNs = 0L
    private val PEAK_THRESHOLD = 10.3f   // |a| must exceed this to count as a step
    private val MIN_STEP_INTERVAL_NS = 250_000_000L  // 250ms = max 4 steps/sec
    private val QUIET_TIMEOUT_NS = 1_500_000_000L     // 1.5s no steps → stopped

    // Current velocity
    private var velocity = 0f  // m/s

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
        stepCount = 0
        stepIntervals.clear()
        velocity = 0f
        lastAMag = 0f
        lastLastAMag = 0f
        lastPeakTimeNs = 0L
        inPeak = false

        startButton.text = "Stop"
        statusText.text = "Tracking..."

        val rate = SensorManager.SENSOR_DELAY_FASTEST
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, rate)
        }

        handler.post(uiUpdateRunnable)
    }

    private fun stopTracking() {
        isTracking = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        startButton.text = "Start"
        statusText.text = "Stopped — $stepCount steps"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

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

        // Compute |a|
        val aMag = sqrt(
            lastAccel[0] * lastAccel[0] +
            lastAccel[1] * lastAccel[1] +
            lastAccel[2] * lastAccel[2]
        )

        // Step detection: peak in |a| (foot strike creates sharp spike)
        // A step peak is when |a| exceeds threshold and we see a local maximum
        if (!inPeak && aMag > PEAK_THRESHOLD) {
            inPeak = true
            peakStartTimeNs = ts
        }

        if (inPeak) {
            // Wait for the peak to come back down
            if (aMag < PEAK_THRESHOLD) {
                inPeak = false

                // Count this as a step (with minimum interval check)
                val timeSinceLastStep = if (lastPeakTimeNs > 0) ts - lastPeakTimeNs else Long.MAX_VALUE

                if (timeSinceLastStep > MIN_STEP_INTERVAL_NS) {
                    stepCount++

                    if (lastPeakTimeNs > 0) {
                        stepIntervals.addLast(timeSinceLastStep)
                        if (stepIntervals.size > 6) stepIntervals.removeFirst()
                    }
                    lastPeakTimeNs = ts
                }
            }
        }

        // Update velocity based on step timing
        val timeSinceLastStep = if (lastPeakTimeNs > 0) ts - lastPeakTimeNs else Long.MAX_VALUE

        if (timeSinceLastStep > QUIET_TIMEOUT_NS) {
            // No steps for 1.5 seconds → stopped
            velocity = 0f
        } else if (stepIntervals.size >= 2) {
            // Calculate speed from step frequency
            val avgIntervalNs = stepIntervals.average()
            val stepFreqHz = 1_000_000_000.0 / avgIntervalNs
            velocity = (STRIDE_LENGTH * stepFreqHz).toFloat()

            // Clamp to reasonable walking/running range
            velocity = velocity.coerceIn(0f, 8f)  // max ~29 km/h
        }
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

        // Step frequency
        val stepFreq = if (stepIntervals.size >= 2) {
            1_000_000_000.0 / stepIntervals.average()
        } else 0.0

        val isStopped = velocity < 0.1f

        statusText.text = if (isStopped) "STOPPED" else "WALKING"
        speedText.text = "%.1f km/h".format(speedKmh)

        val sb = StringBuilder()
        sb.appendLine("|a|=${"%.2f".format(aMag)}  steps=$stepCount")
        sb.appendLine("freq=${"%.1f".format(stepFreq)} Hz  stride=${STRIDE_LENGTH}m")
        sb.appendLine("v=${"%.2f".format(velocity)} m/s  ${"%.0f".format(rateHz)}Hz")
        detailText.text = sb.toString()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(uiUpdateRunnable)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
