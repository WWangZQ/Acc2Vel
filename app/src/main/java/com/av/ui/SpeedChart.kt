package com.av.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Real-time speed chart — shows speed history as a line graph.
 *
 * @param speedHistory list of speed values in km/h (newest last)
 * @param maxSpeedKmh y-axis maximum
 */
@Composable
fun SpeedChart(
    speedHistory: List<Float>,
    maxSpeedKmh: Float = 120f,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val w = size.width
        val h = size.height
        val padding = 40f
        val chartW = w - padding * 2
        val chartH = h - padding * 2

        // Grid lines
        for (i in 0..4) {
            val y = padding + chartH * (1f - i / 4f)
            drawLine(gridColor, Offset(padding, y), Offset(w - padding, y), strokeWidth = 1f)
        }

        if (speedHistory.isEmpty()) return@Canvas

        // Draw speed line
        val path = Path()
        val maxPoints = speedHistory.size

        for (i in speedHistory.indices) {
            val x = padding + chartW * i.toFloat() / (maxPoints - 1).coerceAtLeast(1)
            val y = padding + chartH * (1f - (speedHistory[i] / maxSpeedKmh).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path, lineColor, style = Stroke(width = 3f))

        // Draw data points (last few)
        val showPoints = minOf(20, speedHistory.size)
        for (i in (speedHistory.size - showPoints) until speedHistory.size) {
            val x = padding + chartW * i.toFloat() / (maxPoints - 1).coerceAtLeast(1)
            val y = padding + chartH * (1f - (speedHistory[i] / maxSpeedKmh).coerceIn(0f, 1f))
            drawCircle(lineColor, radius = 4f, center = Offset(x, y))
        }
    }
}
