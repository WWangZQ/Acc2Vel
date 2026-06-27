package com.av.util

/**
 * Simple first-order IIR low-pass filter.
 * y[n] = alpha * x[n] + (1 - alpha) * y[n-1]
 *
 * Alpha = dt / (RC + dt), where RC = 1 / (2π * cutoff_freq).
 */
class LowPassFilter(private val alpha: Float) {
    init {
        require(alpha in 0f..1f) { "Alpha must be in [0, 1]" }
    }

    private var initialized = false
    private var state = FloatArray(0)

    /** Filter a single sample (3-axis vector). */
    fun filter(input: FloatArray): FloatArray {
        if (!initialized) {
            state = input.copyOf()
            initialized = true
            return state
        }
        for (i in state.indices.coerceAtMost(input.size)) {
            state[i] = state[i] + alpha * (input[i] - state[i])
        }
        return state
    }

    /** Reset the filter state. */
    fun reset() {
        initialized = false
        state = FloatArray(0)
    }

    companion object {
        /** Create from cutoff frequency and sample rate. */
        fun fromCutoff(cutoffHz: Float, sampleRateHz: Float): LowPassFilter {
            val rc = 1f / (2f * kotlin.math.PI.toFloat() * cutoffHz)
            val dt = 1f / sampleRateHz
            val alpha = dt / (rc + dt)
            return LowPassFilter(alpha)
        }
    }
}
