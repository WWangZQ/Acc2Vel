package com.av.route

import com.av.data.NavState
import com.av.data.RouteProfile
import com.av.data.Station
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Matches current speed profile against a known metro route.
 *
 * Strategy: particle filter where each particle represents a hypothesis
 * position on the route. Particles are weighted by how well the measured
 * speed matches the expected speed at that position.
 *
 * Also provides speed constraints: e.g., if we know the train is approaching
 * a curve, we can constrain max speed.
 */
class RouteMatcher(
    private val route: RouteProfile,
    particleCount: Int = 200
) {
    private data class Particle(
        var position: Float,    // meters along track
        var weight: Float = 1f
    )

    private val particles = Array(particleCount) {
        Particle(route.totalLength * it.toFloat() / particleCount)
    }
    private var initialized = false

    /**
     * Update particle positions based on estimated velocity.
     * @param speedMps estimated speed in m/s
     * @param dt time step in seconds
     */
    fun propagate(speedMps: Float, dt: Float) {
        if (!initialized) {
            // Initialize uniformly on first call
            for (p in particles) p.weight = 1f / particles.size
            initialized = true
        }

        for (p in particles) {
            p.position += speedMps * dt
            // Wrap around if past end of route
            if (p.position > route.totalLength) {
                p.position -= route.totalLength
            }
        }
    }

    /**
     * Weight particles by speed match quality.
     * @param measuredSpeed current speed in m/s
     * @param tolerance expected speed error in m/s
     */
    fun weightBySpeed(measuredSpeed: Float, tolerance: Float = 5f) {
        for (p in particles) {
            val expectedSpeed = getExpectedSpeedAtPosition(p.position)
            val error = abs(measuredSpeed - expectedSpeed)
            // Gaussian weighting
            p.weight *= expApprox(-(error * error) / (2f * tolerance * tolerance))
        }

        // Normalize weights
        val totalWeight = particles.sumOf { it.weight.toDouble() }.toFloat()
        if (totalWeight > 0f) {
            for (p in particles) p.weight /= totalWeight
        }

        // Resample if effective particle count is too low
        val neff = 1f / particles.sumOf { (it.weight * it.weight).toDouble() }.toFloat()
        if (neff < particles.size / 2f) {
            resample()
        }
    }

    /**
     * Get the estimated position along the route (weighted average).
     */
    fun getEstimatedPosition(): Float {
        return particles.sumOf { (it.position * it.weight).toDouble() }.toFloat()
    }

    /**
     * Get the expected next station based on particle consensus.
     */
    fun getExpectedNextStation(): Station? {
        val estPos = getEstimatedPosition()
        return route.stations.firstOrNull { it.distanceFromStart > estPos }
    }

    /**
     * Get expected speed at a given position (simple lookup from station distances).
     * Between stations: assume cruising at avgSpeed.
     * Near stations: assume deceleration.
     */
    private fun getExpectedSpeedAtPosition(pos: Float): Float {
        val stations = route.stations
        if (stations.isEmpty()) return route.avgSpeed

        // Find which segment we're in
        for (i in 0 until stations.size - 1) {
            val depPos = stations[i].distanceFromStart
            val arrPos = stations[i + 1].distanceFromStart
            if (pos in depPos..arrPos) {
                val segmentLen = arrPos - depPos
                val distToArrival = arrPos - pos
                val distFromDeparture = pos - depPos

                // Deceleration zone: last 500m before station
                val decelZone = 500f
                if (distToArrival < decelZone) {
                    return route.avgSpeed * (distToArrival / decelZone)
                }
                // Acceleration zone: first 300m after station
                val accelZone = 300f
                if (distFromDeparture < accelZone) {
                    return route.avgSpeed * (distFromDeparture / accelZone)
                }
                return route.avgSpeed
            }
        }
        return route.avgSpeed
    }

    private fun resample() {
        val cumulative = FloatArray(particles.size)
        cumulative[0] = particles[0].weight
        for (i in 1 until particles.size) {
            cumulative[i] = cumulative[i - 1] + particles[i].weight
        }

        val newParticles = Array(particles.size) { Particle(0f, 1f / particles.size) }
        val step = 1f / particles.size
        var offset = step * Math.random().toFloat()
        var j = 0
        for (i in particles.indices) {
            while (j < cumulative.size - 1 && cumulative[j] < offset) j++
            newParticles[i].position = particles[j].position
            offset += step
        }
        for (i in particles.indices) {
            particles[i].position = newParticles[i].position
            particles[i].weight = newParticles[i].weight
        }
    }

    fun reset() {
        for (i in particles.indices) {
            particles[i].position = route.totalLength * i.toFloat() / particles.size
            particles[i].weight = 1f / particles.size
        }
        initialized = false
    }

    private fun expApprox(x: Float): Float {
        // Fast approximation for exp — good enough for weighting
        var t = 1f + x / 16f
        t *= t; t *= t; t *= t; t *= t
        return t.coerceIn(0f, 1f)
    }
}
