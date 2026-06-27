package com.av.data

/**
 * Raw sample from one IMU reading (all in SI units).
 * Accelerometer: m/s², Gyroscope: rad/s, Magnetometer: µT, Pressure: hPa
 */
data class ImuSample(
    val timestampNs: Long,
    val accel: FloatArray,          // [ax, ay, az] m/s²
    val gyro: FloatArray,           // [wx, wy, wz] rad/s
    val mag: FloatArray? = null,    // [mx, my, mz] µT (optional, unreliable underground)
    val pressure: Float? = null     // hPa (barometer)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuSample) return false
        return timestampNs == other.timestampNs &&
                accel.contentEquals(other.accel) &&
                gyro.contentEquals(other.gyro) &&
                mag.contentEquals(other.mag) &&
                pressure == other.pressure
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + accel.contentHashCode()
        result = 31 * result + gyro.contentHashCode()
        result = 31 * result + (mag?.contentHashCode() ?: 0)
        result = 31 * result + (pressure?.hashCode() ?: 0)
        return result
    }
}

/** GPS fix when available (above ground) */
data class GpsFix(
    val timestampNs: Long,
    val lat: Double,
    val lon: Double,
    val speed: Float,               // m/s (from GPS hardware)
    val bearing: Float,             // degrees from north
    val accuracy: Float             // estimated horizontal error in meters
)

/**
 * Full navigation state — output of the EKF.
 * All vectors in the local navigation frame (NED or ENU).
 */
data class NavState(
    val timestampNs: Long,
    val velocity: FloatArray,       // [vx, vy, vz] m/s in nav frame
    val speed: Float,               // scalar |velocity| m/s
    val speedKmh: Float,            // speed * 3.6
    val position: FloatArray,       // [x, y, z] relative displacement meters
    val orientation: FloatArray,    // quaternion [w, x, y, z]
    val accelBias: FloatArray,      // estimated accelerometer bias [bx, by, bz]
    val gyroBias: FloatArray,       // estimated gyroscope bias [bx, by, bz]
    val isZupt: Boolean,            // currently in zero-velocity state
    val isUnderground: Boolean,     // GPS unavailable
    val confidence: Float           // 0.0 - 1.0 quality metric
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavState) return false
        return timestampNs == other.timestampNs &&
                velocity.contentEquals(other.velocity) &&
                speed == other.speed &&
                position.contentEquals(other.position) &&
                orientation.contentEquals(other.orientation) &&
                isZupt == other.isZupt
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + velocity.contentHashCode()
        result = 31 * result + speed.hashCode()
        return result
    }

    companion object {
        val ZERO = NavState(
            timestampNs = 0L,
            velocity = floatArrayOf(0f, 0f, 0f),
            speed = 0f,
            speedKmh = 0f,
            position = floatArrayOf(0f, 0f, 0f),
            orientation = floatArrayOf(1f, 0f, 0f, 0f),
            accelBias = floatArrayOf(0f, 0f, 0f),
            gyroBias = floatArrayOf(0f, 0f, 0f),
            isZupt = false,
            isUnderground = false,
            confidence = 0f
        )
    }
}

/** Metro station metadata */
data class Station(
    val name: String,
    val nameZh: String,             // Chinese name for local metro
    val distanceFromStart: Float,   // meters along track from first station
    val expectedDwellTimeSec: Float = 30f,
    val isUnderground: Boolean = true
)

/** Pre-recorded route speed profile for one metro line */
data class RouteProfile(
    val lineName: String,
    val lineNameZh: String,
    val stations: List<Station>,
    val totalLength: Float,         // meters
    val avgSpeed: Float,            // m/s (typical cruising speed)
    val maxSpeed: Float             // m/s
)

/** Event when a station stop is detected */
data class StationEvent(
    val station: Station?,
    val arrivalTime: Long,          // System.currentTimeMillis()
    val dwellTimeMs: Long
)

/** Tracking lifecycle state */
enum class TrackingStatus {
    IDLE,
    COLLECTING,         // sensors active, waiting for calibration
    CALIBRATING,        // user holding still, estimating biases
    TRACKING,           // full pipeline running
    GPS_ONLY,           // IMU failed, falling back to GPS only
    ERROR
}

/** Summary produced when tracking stops */
data class TripSummary(
    val startTime: Long,
    val endTime: Long,
    val maxSpeed: Float,            // m/s
    val avgMovingSpeed: Float,      // m/s (excludes stopped time)
    val stationsVisited: List<StationEvent>,
    val totalDistance: Float         // meters (estimated)
)

/** Info about available sensors on this device */
data class SensorInfo(
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasMagnetometer: Boolean,
    val hasBarometer: Boolean,
    val hasGps: Boolean,
    val accelMaxRange: Float,       // m/s²
    val gyroMaxRange: Float,        // rad/s
    val accelResolution: Float,     // m/s²
    val gyroResolution: Float,      // rad/s
    val maxSamplingRateHz: Int      // SENSOR_DELAY_FASTEST equivalent
)
