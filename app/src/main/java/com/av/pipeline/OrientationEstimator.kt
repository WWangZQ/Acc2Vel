package com.av.pipeline

import com.av.util.Quaternion
import kotlin.math.sqrt

/**
 * Madgwick AHRS (Attitude and Heading Reference System) filter.
 *
 * Estimates orientation from gyroscope integration, corrected by
 * accelerometer (gravity reference) and optionally magnetometer.
 * Uses gradient descent optimization instead of Kalman filter.
 *
 * Reference: Madgwick, S.O.H. (2010)
 * "An efficient orientation filter for inertial and inertial/magnetic sensor arrays"
 *
 * @param sampleRateHz Expected IMU sampling rate (for internal dt default)
 * @param beta Gain parameter: higher = more accel/mag trust (less gyro drift correction speed).
 *             Typical range: 0.01 - 0.5. Start with 0.1.
 */
class OrientationEstimator(
    private val sampleRateHz: Float = 100f,
    var beta: Float = 0.1f
) {
    private var q = Quaternion.IDENTITY
    private var lastTimestampNs: Long = 0L

    /**
     * Update orientation with new IMU data.
     *
     * @param gyro [wx, wy, wz] in rad/s (body frame)
     * @param accel [ax, ay, az] in m/s² (body frame, includes gravity)
     * @param mag [mx, my, mz] in µT (body frame, optional)
     * @param timestampNs sensor timestamp in nanoseconds (optional, for precise dt)
     * @return Updated orientation quaternion
     */
    fun update(
        gyro: FloatArray,
        accel: FloatArray,
        mag: FloatArray? = null,
        timestampNs: Long = 0L
    ): Quaternion {
        // Compute dt
        val dt = if (timestampNs > 0 && lastTimestampNs > 0) {
            (timestampNs - lastTimestampNs) / 1_000_000_000f
        } else {
            1f / sampleRateHz
        }
        lastTimestampNs = timestampNs

        // Validate dt (skip if unreasonably large — indicates gap)
        val safeDt = if (dt in 0.0001f..0.1f) dt else 1f / sampleRateHz

        // Extract gyro components
        val gx = gyro[0]; val gy = gyro[1]; val gz = gyro[2]

        // Normalize accelerometer
        val ax = accel[0]; val ay = accel[1]; val az = accel[2]
        val aNorm = sqrt(ax * ax + ay * ay + az * az)
        if (aNorm < 1e-10f) return q  // skip if accel is zero (shouldn't happen)
        val axN = ax / aNorm; val ayN = ay / aNorm; val azN = aNorm / aNorm

        // Current quaternion components
        var q0 = q.w; var q1 = q.x; var q2 = q.y; var q3 = q.z

        if (mag != null && mag.size >= 3) {
            // ===== With magnetometer: full AHRS =====
            val mx = mag[0]; val my = mag[1]; val mz = mag[2]

            // Reference direction of Earth's magnetic field (in nav frame)
            val h0 = 2f * (mx * (0.5f - q2*q2 - q3*q3) + my * (q1*q2 - q0*q3) + mz * (q1*q3 + q0*q2))
            val h1 = 2f * (mx * (q1*q2 + q0*q3) + my * (0.5f - q1*q1 - q3*q3) + mz * (q2*q3 - q0*q1))
            val b0 = sqrt(h0*h0 + h1*h1)  // horizontal component of mag in nav frame
            val b2 = 2f * (mx * (q1*q3 - q0*q2) + my * (q2*q3 + q0*q1) + mz * (0.5f - q1*q1 - q2*q2))

            // Gradient descent step (objective function Jacobian for accel + mag)
            // f_accel = objective for gravity reference
            val f1 = 2f*(q1*q3 - q0*q2) - axN
            val f2 = 2f*(q0*q1 + q2*q3) - ayN
            val f3 = 2f*(0.5f - q1*q1 - q2*q2) - azN
            // f_mag = objective for magnetic reference
            val f4 = 2f*b0*(0.5f - q2*q2 - q3*q3) + 2f*b2*(q1*q3 - q0*q2) - h0
            val f5 = 2f*b0*(q1*q2 - q0*q3) + 2f*b2*(q0*q1 + q2*q3) - h1
            val f6 = 2f*b0*(q0*q2 + q1*q3) + 2f*b2*(0.5f - q1*q1 - q2*q2) - b2

            // Jacobian for accel
            val j11 = -2f*q2; val j12 = 2f*q3; val j13 = -2f*q0; val j14 = 2f*q1
            val j21 = 2f*q1; val j22 = 2f*q0; val j23 = 2f*q3; val j24 = 2f*q2
            val j31 = 0f; val j32 = -4f*q1; val j33 = -4f*q2; val j34 = 0f

            // Jacobian for mag (additional rows)
            val j41 = -2f*b0*q2 + 2f*b2*q3; val j42 = 2f*b0*q3 + 2f*b2*q2
            val j43 = -2f*b0*q0 - 2f*b2*q1; val j44 = 2f*b0*q1 - 2f*b2*q0
            val j51 = 2f*b0*q1 + 2f*b2*q0; val j52 = 2f*b0*q0 + 2f*b2*q1
            val j53 = 2f*b0*q3 - 2f*b2*q2; val j54 = 2f*b0*q2 + 2f*b2*q3
            val j61 = 2f*b0*q0 - 2f*b2*q1; val j62 = 2f*b0*q1 - 2f*b2*q0
            val j63 = 2f*b0*q2 + 2f*b2*q3; val j64 = -2f*b0*q3 + 2f*b2*q2

            // Gradient = J^T * f
            var s0 = j11*f1 + j21*f2 + j31*f3 + j41*f4 + j51*f5 + j61*f6
            var s1 = j12*f1 + j22*f2 + j32*f3 + j42*f4 + j52*f5 + j62*f6
            var s2 = j13*f1 + j23*f2 + j33*f3 + j43*f4 + j53*f5 + j63*f6
            var s3 = j14*f1 + j24*f2 + j34*f3 + j44*f4 + j54*f5 + j64*f6

            // Normalize step
            val sNorm = sqrt(s0*s0 + s1*s1 + s2*s2 + s3*s3)
            if (sNorm > 1e-10f) {
                s0 /= sNorm; s1 /= sNorm; s2 /= sNorm; s3 /= sNorm
            }

            // Quaternion rate from gyroscope
            val qDot0 = 0.5f * (-q1*gx - q2*gy - q3*gz) - beta * s0
            val qDot1 = 0.5f * ( q0*gx + q2*gz - q3*gy) - beta * s1
            val qDot2 = 0.5f * ( q0*gy - q1*gz + q3*gx) - beta * s2
            val qDot3 = 0.5f * ( q0*gz + q1*gy - q2*gx) - beta * s3

            // Integrate
            q0 += qDot0 * safeDt
            q1 += qDot1 * safeDt
            q2 += qDot2 * safeDt
            q3 += qDot3 * safeDt

        } else {
            // ===== Accelerometer-only: Madgwick AHRS without magnetometer =====

            // Gradient descent step (objective function Jacobian for gravity reference only)
            val f1 = 2f*(q1*q3 - q0*q2) - axN
            val f2 = 2f*(q0*q1 + q2*q3) - ayN
            val f3 = 2f*(0.5f - q1*q1 - q2*q2) - azN

            val j11 = -2f*q2; val j12 = 2f*q3; val j13 = -2f*q0; val j14 = 2f*q1
            val j21 = 2f*q1; val j22 = 2f*q0; val j23 = 2f*q3; val j24 = 2f*q2
            val j31 = 0f; val j32 = -4f*q1; val j33 = -4f*q2; val j34 = 0f

            var s0 = j11*f1 + j21*f2 + j31*f3
            var s1 = j12*f1 + j22*f2 + j32*f3
            var s2 = j13*f1 + j23*f2 + j33*f3
            var s3 = j14*f1 + j24*f2 + j34*f3

            val sNorm = sqrt(s0*s0 + s1*s1 + s2*s2 + s3*s3)
            if (sNorm > 1e-10f) {
                s0 /= sNorm; s1 /= sNorm; s2 /= sNorm; s3 /= sNorm
            }

            // Quaternion rate from gyroscope
            val qDot0 = 0.5f * (-q1*gx - q2*gy - q3*gz) - beta * s0
            val qDot1 = 0.5f * ( q0*gx + q2*gz - q3*gy) - beta * s1
            val qDot2 = 0.5f * ( q0*gy - q1*gz + q3*gx) - beta * s2
            val qDot3 = 0.5f * ( q0*gz + q1*gy - q2*gx) - beta * s3

            q0 += qDot0 * safeDt
            q1 += qDot1 * safeDt
            q2 += qDot2 * safeDt
            q3 += qDot3 * safeDt
        }

        // Normalize quaternion
        q = Quaternion(q0, q1, q2, q3).normalize()
        return q
    }

    /** Get current orientation quaternion. */
    fun getQuaternion(): Quaternion = q

    /** Get 3×3 rotation matrix (body → nav frame), row-major FloatArray[9]. */
    fun getRotationMatrix(): FloatArray = q.toRotationMatrix()

    /** Get Euler angles [roll, pitch, yaw] in radians. */
    fun getEulerAngles(): FloatArray = q.toEulerAngles()

    /** Reset to identity orientation. */
    fun reset() {
        q = Quaternion.IDENTITY
        lastTimestampNs = 0L
    }

    /** Set orientation externally (e.g., from calibration). */
    fun setOrientation(quat: Quaternion) {
        q = quat.normalize()
    }
}
