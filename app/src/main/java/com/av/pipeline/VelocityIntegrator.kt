package com.av.pipeline

/**
 * Integrates acceleration to velocity using trapezoidal rule.
 *
 * v[n] = v[n-1] + (a[n-1] + a[n]) * dt / 2
 * p[n] = p[n-1] + (v[n-1] + v[n]) * dt / 2
 *
 * Handles ZUPT by resetting velocity to zero when signaled.
 */
class VelocityIntegrator {
    private var velocity = floatArrayOf(0f, 0f, 0f)
    private var position = floatArrayOf(0f, 0f, 0f)
    private var prevAccel = floatArrayOf(0f, 0f, 0f)
    private var prevTimestampNs: Long = 0L

    /**
     * Integrate one step.
     *
     * @param navAccel gravity-free acceleration in nav frame [ax, ay, az]
     * @param timestampNs sensor timestamp (nanoseconds)
     */
    fun integrate(navAccel: FloatArray, timestampNs: Long) {
        val dt = if (prevTimestampNs > 0) {
            (timestampNs - prevTimestampNs) / 1_000_000_000f
        } else {
            1f / 200f  // default ~200Hz
        }

        // Guard against bad dt values
        if (dt <= 0f || dt > 0.1f) {
            prevTimestampNs = timestampNs
            prevAccel = navAccel.copyOf()
            return
        }

        // Trapezoidal integration: velocity
        for (i in 0..2) {
            velocity[i] += (prevAccel[i] + navAccel[i]) * dt / 2f
        }

        // Trapezoidal integration: position
        for (i in 0..2) {
            position[i] += velocity[i] * dt
        }

        prevAccel = navAccel.copyOf()
        prevTimestampNs = timestampNs
    }

    /** Zero-velocity update: reset velocity to zero. */
    fun zupt() {
        velocity[0] = 0f
        velocity[1] = 0f
        velocity[2] = 0f
    }

    /** Set velocity externally (e.g., from GPS or EKF correction). */
    fun setVelocity(v: FloatArray) {
        require(v.size >= 3)
        velocity = v.copyOf()
    }

    /** Set position externally. */
    fun setPosition(p: FloatArray) {
        require(p.size >= 3)
        position = p.copyOf()
    }

    fun getVelocity(): FloatArray = velocity.copyOf()
    fun getPosition(): FloatArray = position.copyOf()
    fun getSpeed(): Float = kotlin.math.sqrt(
        velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]
    )

    fun reset() {
        velocity = floatArrayOf(0f, 0f, 0f)
        position = floatArrayOf(0f, 0f, 0f)
        prevAccel = floatArrayOf(0f, 0f, 0f)
        prevTimestampNs = 0L
    }
}
