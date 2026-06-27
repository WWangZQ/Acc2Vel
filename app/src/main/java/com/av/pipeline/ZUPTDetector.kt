package com.av.pipeline

import com.av.data.ImuSample
import kotlin.math.sqrt

/**
 * Detects zero-velocity conditions by monitoring IMU signal variance.
 *
 * When the phone/watch is stationary (e.g., metro at a station):
 * - Accelerometer variance is low (no vibration/acceleration)
 * - Gyroscope variance is low (no rotation)
 * - Mean accelerometer magnitude ≈ g (9.81 m/s²)
 *
 * Uses a sliding window of recent samples to compute statistics.
 */
class ZUPTDetector(
    /** Window size in number of samples (~250ms at 200Hz) */
    var windowSize: Int = 50,
    /** Max accelerometer variance (m/s²)² to declare ZUPT */
    var accelVarianceThreshold: Float = 0.05f,
    /** Max gyroscope variance (rad/s)² to declare ZUPT */
    var gyroVarianceThreshold: Float = 0.01f,
    /** Min consecutive ZUPT windows before declaring a ZUPT event */
    var minConsecutiveWindows: Int = 2
) {
    private val accelBuffer = ArrayDeque<FloatArray>(windowSize + 1)
    private val gyroBuffer = ArrayDeque<FloatArray>(windowSize + 1)
    private var consecutiveZuptWindows = 0
    private var currentlyZupt = false
    private var zuptStartTimeNs: Long = 0L

    /**
     * Feed a new IMU sample. Returns true if currently in ZUPT state.
     */
    fun update(sample: ImuSample): Boolean {
        // Add to buffer
        accelBuffer.addLast(sample.accel.copyOf())
        gyroBuffer.addLast(sample.gyro.copyOf())

        // Trim to window size
        while (accelBuffer.size > windowSize) accelBuffer.removeFirst()
        while (gyroBuffer.size > windowSize) gyroBuffer.removeFirst()

        // Need full window to evaluate
        if (accelBuffer.size < windowSize) return false

        // Compute variances
        val accelVar = computeVariance(accelBuffer)
        val gyroVar = computeVariance(gyroBuffer)

        val isZuptWindow = accelVar < accelVarianceThreshold && gyroVar < gyroVarianceThreshold

        if (isZuptWindow) {
            consecutiveZuptWindows++
            if (consecutiveZuptWindows >= minConsecutiveWindows && !currentlyZupt) {
                currentlyZupt = true
                zuptStartTimeNs = System.nanoTime()
            }
        } else {
            consecutiveZuptWindows = 0
            currentlyZupt = false
        }

        return currentlyZupt
    }

    /** Whether the detector is currently in ZUPT state. */
    fun isInZupt(): Boolean = currentlyZupt

    /** Duration of current ZUPT in milliseconds (0 if not in ZUPT). */
    fun getZuptDurationMs(): Long {
        return if (currentlyZupt) {
            (System.nanoTime() - zuptStartTimeNs) / 1_000_000
        } else 0L
    }

    /** Compute mean of a buffer of 3-vectors. */
    fun computeMean(buffer: Collection<FloatArray>): FloatArray {
        val mean = floatArrayOf(0f, 0f, 0f)
        for (v in buffer) {
            mean[0] += v[0]; mean[1] += v[1]; mean[2] += v[2]
        }
        val n = buffer.size.toFloat()
        mean[0] /= n; mean[1] /= n; mean[2] /= n
        return mean
    }

    /** Compute variance (sum of component variances) of a buffer of 3-vectors. */
    private fun computeVariance(buffer: Collection<FloatArray>): Float {
        val mean = computeMean(buffer)
        var variance = 0f
        for (v in buffer) {
            val dx = v[0] - mean[0]
            val dy = v[1] - mean[1]
            val dz = v[2] - mean[2]
            variance += dx * dx + dy * dy + dz * dz
        }
        return variance / buffer.size.toFloat()
    }

    /** Get current accel magnitude from buffer (for gravity estimation). */
    fun getCurrentAccelMagnitude(): Float {
        if (accelBuffer.isEmpty()) return 0f
        val last = accelBuffer.last()
        return sqrt(last[0]*last[0] + last[1]*last[1] + last[2]*last[2])
    }

    fun reset() {
        accelBuffer.clear()
        gyroBuffer.clear()
        consecutiveZuptWindows = 0
        currentlyZupt = false
        zuptStartTimeNs = 0L
    }
}
