package com.av.pipeline

import com.av.data.NavState
import com.av.data.Station
import com.av.data.StationEvent

/**
 * Higher-level detector that identifies metro station stops.
 * Combines ZUPT detection with dwell-time heuristics.
 *
 * A station stop is characterized by:
 * - ZUPT state lasting 10-120 seconds (too short = traffic light, too long = end of line)
 * - Prior deceleration pattern (optional, for confidence)
 * - Barometric pressure consistency (optional)
 */
class StationDetector(
    /** Minimum ZUPT duration (ms) to consider it a station stop (not just a brief pause) */
    var minDwellMs: Long = 10_000L,
    /** Maximum ZUPT duration (ms) before we assume it's not a station (e.g., end of service) */
    var maxDwellMs: Long = 120_000L,
    /** Known route stations for matching */
    private var stations: List<Station> = emptyList()
) {
    private var zuptActive = false
    private var zuptStartMs: Long = 0L
    private var lastEvent: StationEvent? = null
    private var stationIndex: Int = 0  // expected next station on route

    /**
     * Feed pipeline state. Returns a StationEvent when a station stop completes
     * (i.e., when the train starts moving again after a qualifying dwell).
     */
    fun update(isZupt: Boolean, navState: NavState): StationEvent? {
        val now = System.currentTimeMillis()

        if (isZupt && !zuptActive) {
            // ZUPT just started
            zuptActive = true
            zuptStartMs = now
            return null
        }

        if (!isZupt && zuptActive) {
            // ZUPT ended — check if it qualifies as a station stop
            zuptActive = false
            val dwellMs = now - zuptStartMs

            if (dwellMs in minDwellMs..maxDwellMs) {
                // Match to known station if route is loaded
                val matchedStation = matchNextStation()

                val event = StationEvent(
                    station = matchedStation,
                    arrivalTime = zuptStartMs,
                    dwellTimeMs = dwellMs
                )
                lastEvent = event
                stationIndex++
                return event
            }
        }

        return null
    }

    /** Match current ZUPT to the next expected station on the route. */
    private fun matchNextStation(): Station? {
        if (stations.isEmpty()) return null
        return if (stationIndex < stations.size) stations[stationIndex] else null
    }

    /** Set route stations for matching. */
    fun setStations(stationList: List<Station>) {
        stations = stationList
        stationIndex = 0
    }

    /** Reset state (e.g., new trip). */
    fun reset() {
        zuptActive = false
        zuptStartMs = 0L
        lastEvent = null
        stationIndex = 0
    }

    fun getLastEvent(): StationEvent? = lastEvent
    fun getCurrentStationIndex(): Int = stationIndex
}
