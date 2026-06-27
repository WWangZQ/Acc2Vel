package com.av

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal sensor probe Activity — zero dependencies beyond Android SDK.
 *
 * Purpose: verify that SensorManager returns valid data on OPPO Watch 3 Pro
 * (ColorOS Watch 3.0). If this works, the full Compose app will work too.
 *
 * Displays:
 * - All available sensors on the device
 * - Real-time accelerometer readings
 * - Real-time gyroscope readings
 * - Magnetometer (if available)
 * - Barometer (if available)
 * - Sample rate measurement
 */
class SensorProbeActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var outputText: TextView
    private lateinit var sensorListText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var sampleCount = 0L
    private var startTimeNs = 0L
    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastMag = floatArrayOf(0f, 0f, 0f)
    private var lastPressure = 0f
    private var hasAccel = false
    private var hasGyro = false
    private var hasMag = false
    private var hasBaro = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 200)  // update UI at 5 Hz
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Build simple UI
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // Title
        layout.addView(TextView(this).apply {
            text = "A→V Sensor Probe"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        // Sensor list
        sensorListText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "Loading sensors..."
        }
        layout.addView(sensorListText)

        // Separator
        layout.addView(TextView(this).apply {
            text = "\n─── Live Readings ───"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        })

        // Live readings
        outputText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(outputText)

        scroll.addView(layout)
        setContentView(scroll)

        // Enumerate all sensors
        enumerateSensors()

        // Register for IMU data
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
            sb.appendLine("  vendor=${s.vendor}, range=${s.maximumRange}, res=${s.resolution}")
            sb.appendLine("  minDelay=${s.minDelay}µs, power=${s.power}mA")
        }

        sensorListText.text = sb.toString()
        Log.i("SensorProbe", sb.toString())
    }

    private fun startListening() {
        val rate = SensorManager.SENSOR_DELAY_FASTEST

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, rate)
            hasAccel = true
            Log.i("SensorProbe", "Registered ACCELEROMETER")
        } ?: Log.w("SensorProbe", "No accelerometer found!")

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, rate)
            hasGyro = true
            Log.i("SensorProbe", "Registered GYROSCOPE")
        } ?: Log.w("SensorProbe", "No gyroscope found!")

        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, rate)
            hasMag = true
            Log.i("SensorProbe", "Registered MAGNETIC_FIELD")
        } ?: Log.w("SensorProbe", "No magnetometer found!")

        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, rate)
            hasBaro = true
            Log.i("SensorProbe", "Registered PRESSURE")
        } ?: Log.w("SensorProbe", "No barometer found!")

        startTimeNs = System.nanoTime()
        handler.post(updateRunnable)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                sampleCount++
            }
            Sensor.TYPE_GYROSCOPE -> lastGyro = event.values.copyOf()
            Sensor.TYPE_MAGNETIC_FIELD -> lastMag = event.values.copyOf()
            Sensor.TYPE_PRESSURE -> lastPressure = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.i("SensorProbe", "Accuracy: ${sensor.name} → $accuracy")
    }

    private fun updateDisplay() {
        val elapsed = (System.nanoTime() - startTimeNs) / 1_000_000_000.0
        val hz = if (elapsed > 0) sampleCount / elapsed else 0.0

        val sb = StringBuilder()
        sb.appendLine("Sample rate: ${"%.1f".format(hz)} Hz (n=$sampleCount)")
        sb.appendLine("Elapsed: ${"%.1f".format(elapsed)}s\n")

        if (hasAccel) {
            sb.appendLine("ACCEL (m/s²):")
            sb.appendLine("  X: ${"%+.4f".format(lastAccel[0])}")
            sb.appendLine("  Y: ${"%+.4f".format(lastAccel[1])}")
            sb.appendLine("  Z: ${"%+.4f".format(lastAccel[2])}")
            val mag = kotlin.math.sqrt(
                lastAccel[0]*lastAccel[0] + lastAccel[1]*lastAccel[1] + lastAccel[2]*lastAccel[2]
            )
            sb.appendLine("  |a|: ${"%.4f".format(mag)}")
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
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(updateRunnable)
    }

    override fun onResume() {
        super.onResume()
        startListening()
    }
}
