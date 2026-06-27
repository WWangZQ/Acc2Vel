package com.av.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circular velocity gauge — displays current speed like a speedometer.
 *
 * @param speedKmh current speed in km/h
 * @param maxSpeedKmh maximum speed for the gauge scale (default 120 km/h)
 * @param isZupt whether currently in zero-velocity state
 */
@Composable
fun VelocityGauge(
    speedKmh: Float,
    maxSpeedKmh: Float = 120f,
    isZupt: Boolean = false,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh.coerceIn(0f, maxSpeedKmh),
        animationSpec = tween(durationMillis = 200),
        label = "speed"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val arcColor = if (isZupt) onSurfaceVariant else primaryColor
    val needleColor = if (isZupt) onSurfaceVariant else errorColor

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val padding = strokeWidth / 2 + 8.dp.toPx()
            val arcSize = Size(size.width - padding * 2, size.height - padding * 2)
            val topLeft = Offset(padding, padding)

            // Background arc
            drawArc(
                color = surfaceVariant,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Value arc
            val sweepAngle = (animatedSpeed / maxSpeedKmh * 270f).coerceIn(0f, 270f)
            drawArc(
                color = arcColor,
                startAngle = 135f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Tick marks
            val tickCount = 12
            for (i in 0..tickCount) {
                val angle = Math.toRadians((135.0 + i * 270.0 / tickCount).toDouble())
                val innerRadius = (size.width / 2 - padding - 20.dp.toPx())
                val outerRadius = (size.width / 2 - padding - 8.dp.toPx())
                val cx = size.width / 2
                val cy = size.height / 2
                val startX = cx + innerRadius * kotlin.math.cos(angle).toFloat()
                val startY = cy + innerRadius * kotlin.math.sin(angle).toFloat()
                val endX = cx + outerRadius * kotlin.math.cos(angle).toFloat()
                val endY = cy + outerRadius * kotlin.math.sin(angle).toFloat()
                drawLine(
                    color = onSurfaceVariant,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = if (i % 3 == 0) 3.dp.toPx() else 1.5.dp.toPx()
                )
            }

            // Needle
            val needleAngle = Math.toRadians((135.0 + animatedSpeed / maxSpeedKmh * 270.0).toDouble())
            val needleLength = size.width / 2 - padding - 30.dp.toPx()
            val cx = size.width / 2
            val cy = size.height / 2
            val needleEndX = cx + needleLength * kotlin.math.cos(needleAngle).toFloat()
            val needleEndY = cy + needleLength * kotlin.math.sin(needleAngle).toFloat()
            drawLine(
                color = needleColor,
                start = Offset(cx, cy),
                end = Offset(needleEndX, needleEndY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Center dot
            drawCircle(needleColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
        }

        // Text overlay
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%.1f".format(animatedSpeed),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = onSurface
            )
            Text(
                "km/h",
                fontSize = 14.sp,
                color = onSurfaceVariant
            )
        }
    }
}
