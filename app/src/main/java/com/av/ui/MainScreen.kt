package com.av.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.av.data.NavState
import com.av.data.SensorInfo
import com.av.data.TrackingStatus
import com.av.service.TrackingService
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Phase 1 main screen — shows raw sensor data and basic controls.
 * Will evolve to show velocity gauge and chart in Phase 5.
 */
@Composable
fun MainScreen(
    serviceProvider: () -> TrackingService?,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit
) {
    val service = serviceProvider()

    val status by (service?.status ?: kotlinx.coroutines.flow.MutableStateFlow(TrackingStatus.IDLE)).collectAsState()
    val latestImu by (service?.latestImu ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val latestGps by (service?.latestGps ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val imuCount by (service?.imuCount ?: kotlinx.coroutines.flow.MutableStateFlow(0L)).collectAsState()
    val navState by (service?.navState ?: kotlinx.coroutines.flow.MutableStateFlow(com.av.data.NavState.ZERO)).collectAsState()

    val isTracking = status == TrackingStatus.COLLECTING ||
            status == TrackingStatus.TRACKING ||
            status == TrackingStatus.CALIBRATING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status indicator
        StatusIndicator(status)

        Spacer(Modifier.height(16.dp))

        // Control button
        Button(
            onClick = { if (isTracking) onStopTracking() else onStartTracking() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = animateColorAsState(
                    if (isTracking) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ).value
            )
        ) {
            Icon(
                if (isTracking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isTracking) "Stop Tracking" else "Start Tracking",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        // Sample counter
        if (isTracking) {
            Text(
                "Samples: $imuCount · Status: ${status.name}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }

        // Velocity gauge (visible when tracking with calibrated EKF)
        if (status == TrackingStatus.TRACKING) {
            VelocityGauge(
                speedKmh = navState.speedKmh,
                isZupt = navState.isZupt,
                modifier = Modifier.size(240.dp)
            )

            Spacer(Modifier.height(8.dp))

            // Confidence and bias info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SensorRow("Speed", "%.2f m/s (%.1f km/h)".format(navState.speed, navState.speedKmh))
                    SensorRow("Confidence", "%.0f%%".format(navState.confidence * 100))
                    SensorRow("Accel bias", "%+.4f, %+.4f, %+.4f".format(
                        navState.accelBias[0], navState.accelBias[1], navState.accelBias[2]
                    ))
                    SensorRow("Gyro bias", "%+.4f, %+.4f, %+.4f".format(
                        navState.gyroBias[0], navState.gyroBias[1], navState.gyroBias[2]
                    ))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Speed chart
            val speedHistory = service?.speedHistoryList ?: emptyList()
            if (speedHistory.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Speed History",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                        SpeedChart(speedHistory = speedHistory)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // Calibration message
        if (status == TrackingStatus.CALIBRATING) {
            Text(
                "Calibrating — hold still for 2 seconds...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(16.dp))
        }

        // Raw accelerometer card
        SensorCard(
            title = "Accelerometer",
            unit = "m/s²",
            values = latestImu?.accel,
            color = Color(0xFF1565C0)
        )

        Spacer(Modifier.height(8.dp))

        // Raw gyroscope card
        SensorCard(
            title = "Gyroscope",
            unit = "rad/s",
            values = latestImu?.gyro,
            color = Color(0xFF00897B)
        )

        Spacer(Modifier.height(8.dp))

        // Barometer
        latestImu?.pressure?.let { pressure ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Barometer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "%.1f hPa".format(pressure),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // GPS card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GPS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val gps = latestGps
                if (gps != null) {
                    SensorRow("Speed", "%.1f m/s (%.1f km/h)".format(gps.speed, gps.speed * 3.6f))
                    SensorRow("Position", "%.5f, %.5f".format(gps.lat, gps.lon))
                    SensorRow("Accuracy", "%.1f m".format(gps.accuracy))
                    SensorRow("Bearing", "%.0f°".format(gps.bearing))
                } else {
                    Text(
                        "Waiting for GPS fix...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Sensor info card
        service?.getSensorInfo()?.let { info ->
            SensorInfoCard(info)
        }
    }
}

@Composable
private fun StatusIndicator(status: TrackingStatus) {
    val (color, text) = when (status) {
        TrackingStatus.IDLE -> Color.Gray to "Idle"
        TrackingStatus.COLLECTING -> Color(0xFF1565C0) to "Collecting"
        TrackingStatus.CALIBRATING -> Color(0xFFFFA000) to "Calibrating"
        TrackingStatus.TRACKING -> Color(0xFF2E7D32) to "Tracking"
        TrackingStatus.GPS_ONLY -> Color(0xFFFF6F00) to "GPS Only"
        TrackingStatus.ERROR -> Color(0xFFD32F2F) to "Error"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SensorCard(title: String, unit: String, values: FloatArray?, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "$title ($unit)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.height(8.dp))
            if (values != null && values.size >= 3) {
                SensorRow("X", "%+.4f".format(values[0]))
                SensorRow("Y", "%+.4f".format(values[1]))
                SensorRow("Z", "%+.4f".format(values[2]))
                Spacer(Modifier.height(8.dp))
                // Mini vector visualization
                AccelVectorCanvas(values, color)
            } else {
                Text(
                    "No data",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SensorRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Mini canvas showing the accelerometer vector as a line from center.
 */
@Composable
private fun AccelVectorCanvas(values: FloatArray, color: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        val cx = size.width / 2
        val cy = size.height / 2
        val scale = min(size.width, size.height) / 2 * 0.7f / 20f  // scale so ~20 m/s² fills half

        // Axes
        drawLine(Color.Gray, Offset(cx - 20, cy), Offset(cx + 20, cy), strokeWidth = 1f)
        drawLine(Color.Gray, Offset(cx, cy - 20), Offset(cx, cy + 20), strokeWidth = 1f)

        // Vector: show x-y projection (gravity will dominate Z when flat)
        val vx = values[0] * scale
        val vy = values[1] * scale

        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(cx + vx, cy - vy)  // y-axis inverted for screen
        }
        drawPath(path, color, style = Stroke(width = 3f))

        // Arrow tip
        val tipX = cx + vx
        val tipY = cy - vy
        drawCircle(color, radius = 5f, center = Offset(tipX, tipY))
    }
}

@Composable
private fun SensorInfoCard(info: SensorInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Device Sensors", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            SensorRow("Accelerometer", if (info.hasAccelerometer) "✓ (%.1f m/s² range)".format(info.accelMaxRange) else "✗")
            SensorRow("Gyroscope", if (info.hasGyroscope) "✓ (%.1f rad/s range)".format(info.gyroMaxRange) else "✗")
            SensorRow("Magnetometer", if (info.hasMagnetometer) "✓" else "✗")
            SensorRow("Barometer", if (info.hasBarometer) "✓" else "✗")
            SensorRow("Max sample rate", "${info.maxSamplingRateHz} Hz")
            SensorRow("Accel resolution", "%.6f m/s²".format(info.accelResolution))
            SensorRow("Gyro resolution", "%.6f rad/s".format(info.gyroResolution))
        }
    }
}
