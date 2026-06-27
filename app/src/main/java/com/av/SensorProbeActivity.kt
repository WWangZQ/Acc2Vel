package com.av

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Sensor probe Activity — verifies SensorManager on OPPO Watch 3 Pro.
 *
 * Fixes from v1:
 * - Keep screen on (FLAG_KEEP_SCREEN_ON + WakeLock) to prevent rate throttling
 * - Use SensorEvent.timestamp (elapsed realtime clock) for accurate rate measurement
 * - Show actual hardware sample interval, not wall-clock rate
 */
class SensorProbeActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var outputText: TextView
    private lateinit var sensorListText: TextView
    private lateinit var wakeLock: PowerManager.WakeLock

    private val handler = Handler(Looper.getMainLooper())
    private var sampleCount = 0L
    private var firstTimestampNs = 0L
    private var lastTimestampNs = 0L
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastMag = floatArrayOf(0f, 0f, 0f)
    private var lastPressure = 0f
    private var hasAccel = false
    private var hasGyro = false
    private var hasMag = false
    private var hasBaro = false

    // Rolling rate calculation (last 100 samples)
    private val recentIntervals = ArrayDeque<Long>(100)

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Acquire partial wake lock (keep CPU running even if screen somehow turns off)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Acc2Vel:SensorProbe")
        wakeLock.acquire(60 * 60 * 1000L)  // 1 hour max

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Build simple UI
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        layout.addView(TextView(this).apply {
            text = "A→V Sensor Probe v2"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        })

        sensorListText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = "Loading sensors..."
        }
        layout.addView(sensorListText)

        layout.addView(TextView(this).apply {
            text = "\n─── Live Readings ───"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 8)
        })

        outputText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(outputText)

        scroll.addView(layout)
        setContentView(scroll)

        enumerateSensors()
        startListening()
    }

    private fun enumerateSensors() {
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val sb = StringBuilder()
        sb.appendLine("Found ${allSensors.size} sensors:\n")

        for (s in allSensors) {
            val typeStr = when (s.type) {
                Sensor.TYPE_ACCELEROMETER -> "ACCEL"
                Sensor.TYPE_GYROSCOPE -> "GYRO"
                Sensor.TYPE_MAGNETIC_FIELD -> "MAG"
                Sensor.TYPE_PRESSURE -> "BARO"
                Sensor.TYPE_HEART_RATE -> "HR"
                Sensor.TYPE_STEP_COUNTER -> "STEPS"
                Sensor.TYPE_STEP_DETECTOR -> "STEP_DET"
                Sensor.TYPE_SIGNIFICANT_MOTION -> "MOTION"
                Sensor.TYPE_PROXIMITY -> "PROX"
                Sensor.TYPE_LIGHT -> "LIGHT"
                Sensor.TYPE_ROTATION_VECTOR -> "ROT"
                Sensor.TYPE_GAME_ROTATION_VECTOR -> "GAME_ROT"
                Sensor.TYPE_GRAVITY -> "GRAVITY"
                else -> "TYPE_${s.type}"
            }
            sb.appendLine("[$typeStr] ${s.name}")
            sb.appendLine("  vendor=${s.vendor} range=${s.maximumRange} res=${s.resolution}")
            sb.appendLine("  minDelay=${s.minDelay}µs power=${s.power}mA")
        }

        sensorListText.text = sb.toString()
        Log.i("SensorProbe", sb.toString())
    }

    private fun startListening() {
        val rate = SensorManager.SENSOR_DELAY_FASTEST

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, rate)
            hasAccel = true
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, rate)
            hasGyro = true
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, rate)
            hasMag = true
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, rate)
            hasBaro = true
        }

        handler.post(updateRunnable)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Use SensorEvent.timestamp (elapsed realtime, nanoseconds) for rate calculation
        val ts = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                sampleCount++

                if (firstTimestampNs == 0L) {
                    firstTimestampNs = ts
                } else if (lastTimestampNs > 0) {
                    val interval = ts - lastTimestampNs
                    if (interval > 0 && interval < 100_000_000) {  // < 100ms = valid
                        recentIntervals.addLast(interval)
                        if (recentIntervals.size > 100) recentIntervals.removeFirst()
                    }
                }
                lastTimestampNs = ts
            }
            Sensor.TYPE_GYROSCOPE -> lastGyro = event.values.copyOf()
            Sensor.TYPE_MAGNETIC_FIELD -> lastMag = event.values.copyOf()
            Sensor.TYPE_PRESSURE -> lastPressure = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updateDisplay() {
        val sb = StringBuilder()

        // Sample count and total elapsed
        val totalElapsedSec = if (firstTimestampNs > 0 && lastTimestampNs > firstTimestampNs) {
            (lastTimestampNs - firstTimestampNs) / 1_000_000_000.0
        } else 0.0

        // Rolling rate from actual sensor intervals (most accurate)
        val rollingRateHz = if (recentIntervals.isNotEmpty()) {
            val avgIntervalNs = recentIntervals.average()
            1_000_000_000.0 / avgIntervalNs
        } else 0.0

        // Interval stats
        val minIntervalUs = if (recentIntervals.isNotEmpty()) recentIntervals.min() / 1000.0 else 0.0
        val maxIntervalUs = if (recentIntervals.isNotEmpty()) recentIntervals.max() / 1000.0 else 0.0

        sb.appendLine("Samples: $sampleCount")
        sb.appendLine("Rate: ${"%.1f".format(rollingRateHz)} Hz")
        sb.appendLine("Interval: ${"%.1f".format(minIntervalUs)}-${"%.1f".format(maxIntervalUs)} µs")
        sb.appendLine("Elapsed: ${"%.1f".format(totalElapsedSec)}s")
        sb.appendLine("Screen: ON (wake lock held)")
        sb.appendLine()

        if (hasAccel) {
            sb.appendLine("ACCEL (m/s²):")
            sb.appendLine("  X: ${"%+.4f".format(lastAccel[0])}")
            sb.appendLine("  Y: ${"%+.4f".format(lastAccel[1])}")
            sb.appendLine("  Z: ${"%+.4f".format(lastAccel[2])}")
            val mag = kotlin.math.sqrt(
                lastAccel[0]*lastAccel[0] + lastAccel[1]*lastAccel[1] + lastAccel[2]*lastAccel[2]
            )
            sb.appendLine("  |a|: ${"%.4f".format(mag)}  (bias: ${"%+.2f".format(mag - 9.80665f)})")
        } else {
            sb.appendLine("ACCEL: NOT AVAILABLE")
        }
        sb.appendLine()

        if (hasGyro) {
            sb.appendLine("GYRO (rad/s):")
            sb.appendLine("  X: ${"%+.4f".format(lastGyro[0])}")
            sb.appendLine("  Y: ${"%+.4f".format(lastGyro[1])}")
            sb.appendLine("  Z: ${"%+.4f".format(lastGyro[2])}")
        } else {
            sb.appendLine("GYRO: NOT AVAILABLE")
        }
        sb.appendLine()

        if (hasMag) {
            sb.appendLine("MAG (µT):")
            sb.appendLine("  X: ${"%+.2f".format(lastMag[0])}")
            sb.appendLine("  Y: ${"%+.2f".format(lastMag[1])}")
            sb.appendLine("  Z: ${"%+.2f".format(lastMag[2])}")
        } else {
            sb.appendLine("MAG: NOT AVAILABLE")
        }
        sb.appendLine()

        if (hasBaro) {
            sb.appendLine("BARO: ${"%.2f".format(lastPressure)} hPa")
        } else {
            sb.appendLine("BARO: NOT AVAILABLE")
        }

        outputText.text = sb.toString()
    }

    override fun onPause() {
        super.onPause()
        // Don't unregister — keep collecting even if screen dims
    }

    override fun onResume() {
        super.onResume()
        if (!hasAccel) startListening()
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
