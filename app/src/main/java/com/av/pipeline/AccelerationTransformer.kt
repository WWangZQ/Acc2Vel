package com.av.pipeline

import com.av.util.Matrix3x3
import com.av.util.Quaternion

/**
 * Transforms accelerometer readings from body frame to navigation frame
 * and removes gravity.
 *
 * Body frame: sensor-fixed (XYZ axes of the IMU chip).
 * Navigation frame: local-level frame aligned with gravity + magnetic north.
 *   X = North, Y = East, Z = Down (NED convention).
 *
 * a_nav = R(q) × (a_body - bias) - [0, 0, g]
 *
 * Where R(q) is the rotation matrix from body to nav frame (computed from
 * the orientation quaternion), and g = 9.81 m/s².
 */
class AccelerationTransformer {
    companion object {
        private const val GRAVITY = 9.80665f  // m/s², standard gravity
    }

    private var accelBias = floatArrayOf(0f, 0f, 0f)
    private var lastNavAccel = floatArrayOf(0f, 0f, 0f)

    /**
     * Transform body-frame acceleration to nav-frame, gravity-free.
     *
     * @param bodyAccel raw accelerometer reading [ax, ay, az] m/s² (body frame)
     * @param orientation current orientation quaternion (body → nav)
     * @return gravity-free acceleration in nav frame [ax, ay, az] m/s²
     */
    fun transform(bodyAccel: FloatArray, orientation: Quaternion): FloatArray {
        // Subtract estimated bias
        val corrected = floatArrayOf(
            bodyAccel[0] - accelBias[0],
            bodyAccel[1] - accelBias[1],
            bodyAccel[2] - accelBias[2]
        )

        // Rotate to nav frame: a_nav = R * a_body
        val navAccel = orientation.rotate(corrected)

        // Remove gravity (assuming NED frame: gravity points +Z)
        navAccel[2] -= GRAVITY

        lastNavAccel = navAccel.copyOf()
        return navAccel
    }

    /**
     * Get the horizontal speed component (forward+lateral in nav frame).
     * Returns |v_horizontal| = sqrt(vx² + vy²).
     */
    fun getHorizontalAcceleration(navAccel: FloatArray): Float {
        return kotlin.math.sqrt(navAccel[0] * navAccel[0] + navAccel[1] * navAccel[1])
    }

    /**
     * Update accelerometer bias (called from EKF or during calibration).
     */
    fun setBias(bias: FloatArray) {
        require(bias.size >= 3)
        accelBias = bias.copyOf()
    }

    fun getBias(): FloatArray = accelBias.copyOf()

    fun getLastNavAccel(): FloatArray = lastNavAccel.copyOf()

    fun reset() {
        accelBias = floatArrayOf(0f, 0f, 0f)
        lastNavAccel = floatArrayOf(0f, 0f, 0f)
    }
}
