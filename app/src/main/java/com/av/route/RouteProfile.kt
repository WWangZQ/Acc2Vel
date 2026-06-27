package com.av.route

import com.av.data.RouteProfile
import com.av.data.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.content.Context

/**
 * JSON-serializable route profile format.
 * Load from assets or user-provided JSON files.
 */
@Serializable
data class RouteProfileJson(
    val lineName: String,
    val lineNameZh: String,
    val stations: List<StationJson>,
    val totalLengthMeters: Float,
    val avgSpeedMps: Float,
    val maxSpeedMps: Float
) {
    fun toRouteProfile(): RouteProfile {
        return RouteProfile(
            lineName = lineName,
            lineNameZh = lineNameZh,
            stations = stations.map { it.toStation() },
            totalLength = totalLengthMeters,
            avgSpeed = avgSpeedMps,
            maxSpeed = maxSpeedMps
        )
    }
}

@Serializable
data class StationJson(
    val name: String,
    val nameZh: String,
    val distanceFromStartMeters: Float,
    val expectedDwellTimeSec: Float = 30f,
    val isUnderground: Boolean = true
) {
    fun toStation(): Station {
        return Station(
            name = name,
            nameZh = nameZh,
            distanceFromStart = distanceFromStartMeters,
            expectedDwellTimeSec = expectedDwellTimeSec,
            isUnderground = isUnderground
        )
    }
}

/**
 * Load a route profile from a JSON string.
 */
fun loadRouteProfile(json: String): RouteProfile {
    val parsed = Json.decodeFromString<RouteProfileJson>(json)
    return parsed.toRouteProfile()
}

/**
 * Load a route profile from app assets.
 */
fun loadRouteProfileFromAssets(context: Context, fileName: String): RouteProfile? {
    return try {
        val json = context.assets.open("routes/$fileName").bufferedReader().use { it.readText() }
        loadRouteProfile(json)
    } catch (e: Exception) {
        null
    }
}
