package com.av.ekf

import com.av.data.ImuSample
import com.av.data.NavState
import com.av.data.GpsFix
import com.av.pipeline.AccelerationTransformer
import com.av.pipeline.OrientationEstimator
import com.av.pipeline.VelocityIntegrator
import com.av.pipeline.ZUPTDetector
import com.av.util.Quaternion
import kotlin.math.sqrt

/**
 * Extended Kalman Filter for IMU-based velocity estimation.
 *
 * Uses an error-state formulation:
 *   Nominal state: position(3), velocity(3), quaternion(4), accel_bias(3), gyro_bias(3) = 16
 *   Error state for covariance: dp(3), dv(3), dtheta(3), dba(3), dbg(3) = 15
 *
 * The quaternion perturbation is represented as a 3-vector (small-angle rotation),
 * which makes the covariance matrix 15×15 instead of 16×16.
 *
 * Pipeline:
 *   1. predict() — called at IMU rate (~200Hz), propagates state using gyro+accel
 *   2. updateZupt() — zero-velocity correction when stationary
 *   3. updateGps() — GPS velocity/position correction when available
 *   4. getState() — read the current NavState for UI
 */
class ExtendedKalmanFilter {

    // ========== Nominal state (16) ==========
    private var position = floatArrayOf(0f, 0f, 0f)    // NED position (m)
    private var velocity = floatArrayOf(0f, 0f, 0f)    // NED velocity (m/s)
    private var orientation = Quaternion.IDENTITY       // body → nav
    private var accelBias = floatArrayOf(0f, 0f, 0f)   // accelerometer bias (m/s²)
    private var gyroBias = floatArrayOf(0f, 0f, 0f)    // gyroscope bias (rad/s)

    // ========== Error-state covariance (15×15) ==========
    // State order: dp(3), dv(3), dtheta(3), dba(3), dbg(3)
    private val P = Array(15) { FloatArray(15) }

    // ========== Process noise (15×15) ==========
    private val Q = Array(15) { FloatArray(15) }

    // ========== Internal processors ==========
    private val orientationEstimator = OrientationEstimator(200f, beta = 0.04f)
    private val accelTransformer = AccelerationTransformer()
    private val velocityIntegrator = VelocityIntegrator()
    private val zuptDetector = ZUPTDetector()

    private var lastTimestampNs: Long = 0L
    private var initialized = false

    init {
        initializeCovariance()
        initializeProcessNoise()
    }

    /**
     * Set initial process noise parameters.
     * These are the tuning knobs for the filter.
     */
    private fun initializeProcessNoise() {
        // Position process noise (m²/s³) — mainly driven by velocity noise
        for (i in 0..2) Q[i][i] = 0.01f

        // Velocity process noise — accelerometer noise
        for (i in 3..5) Q[i][i] = 0.5f

        // Orientation process noise — gyroscope noise
        for (i in 6..8) Q[i][i] = 0.01f

        // Accel bias random walk — very slow drift
        for (i in 9..11) Q[i][i] = 0.0001f

        // Gyro bias random walk — very slow drift
        for (i in 12..14) Q[i][i] = 0.00001f
    }

    private fun initializeCovariance() {
        // Initial uncertainty
        for (i in 0..2) P[i][i] = 1f          // position: ±1m
        for (i in 3..5) P[i][i] = 1f          // velocity: ±1 m/s
        for (i in 6..8) P[i][i] = 0.1f        // orientation: ±0.1 rad
        for (i in 9..11) P[i][i] = 0.1f       // accel bias: ±0.1 m/s²
        for (i in 12..14) P[i][i] = 0.01f     // gyro bias: ±0.01 rad/s
    }

    /**
     * Initialize the filter. Call once before first predict().
     * @param accelMean mean accelerometer reading during stationary period
     * @param gyroMean mean gyroscope reading during stationary period
     */
    fun initialize(accelMean: FloatArray, gyroMean: FloatArray) {
        // Set initial orientation from accelerometer (assumes phone is stationary)
        // gravity direction tells us roll and pitch
        val ax = accelMean[0]; val ay = accelMean[1]; val az = accelMean[2]
        val aNorm = sqrt(ax*ax + ay*ay + az*az)
        if (aNorm > 1f) {
            // Compute roll and pitch from gravity
            val roll = kotlin.math.atan2(ay, az)
            val pitch = kotlin.math.atan2(-ax, sqrt(ay*ay + az*az))

            // Build quaternion from roll/pitch (yaw = 0 = unknown)
            val cr = kotlin.math.cos(roll/2f); val sr = kotlin.math.sin(roll/2f)
            val cp = kotlin.math.cos(pitch/2f); val sp = kotlin.math.sin(pitch/2f)
            orientation = Quaternion(
                w = cr * cp,
                x = sr * cp,
                y = cr * sp,
                z = -sr * sp
            )
        }

        // Set biases from stationary readings
        accelBias = floatArrayOf(
            accelMean[0],
            accelMean[1],
            accelMean[2] - 9.80665f  // subtract expected gravity
        )
        gyroBias = gyroMean.copyOf()

        // Update internal processors
        orientationEstimator.setOrientation(orientation)
        accelTransformer.setBias(accelBias)

        position = floatArrayOf(0f, 0f, 0f)
        velocity = floatArrayOf(0f, 0f, 0f)
        lastTimestampNs = 0L
        initialized = true
    }

    /**
     * Predict step — called at IMU rate (~200Hz).
     * Propagates the nominal state and error-state covariance forward.
     */
    fun predict(sample: ImuSample) {
        if (!initialized) return

        val dt = if (lastTimestampNs > 0) {
            (sample.timestampNs - lastTimestampNs) / 1_000_000_000f
        } else {
            1f / 200f
        }
        lastTimestampNs = sample.timestampNs

        if (dt <= 0f || dt > 0.1f) return  // skip bad timestamps

        // ===== Update nominal state =====

        // Correct gyro with bias
        val gyroCorrected = floatArrayOf(
            sample.gyro[0] - gyroBias[0],
            sample.gyro[1] - gyroBias[1],
            sample.gyro[2] - gyroBias[2]
        )

        // Update orientation via Madgwick
        orientation = orientationEstimator.update(
            gyroCorrected, sample.accel, sample.mag, sample.timestampNs
        )

        // Correct accel with bias and transform to nav frame
        val navAccel = accelTransformer.transform(sample.accel, orientation)

        // Integrate velocity and position (trapezoidal)
        velocityIntegrator.integrate(navAccel, sample.timestampNs)
        velocity = velocityIntegrator.getVelocity()
        position = velocityIntegrator.getPosition()

        // ===== Propagate error-state covariance =====
        // P = F * P * F^T + Q * dt
        propagateCovariance(dt, navAccel, gyroCorrected)
    }

    /**
     * Zero-velocity measurement update.
     * When the train is stationary, velocity = [0,0,0].
     */
    fun updateZupt() {
        if (!initialized) return

        // Apply ZUPT to integrator
        velocityIntegrator.zupt()
        velocity = floatArrayOf(0f, 0f, 0f)

        // EKF measurement update: z = H*x + v, where z = [0,0,0] (velocity)
        // H = [0, I, 0, 0, 0] (observation matrix for velocity states)
        // Innovation: y = z_measured - H*x_predicted = -velocity
        val R_zupt = 0.01f  // ZUPT measurement noise (m/s)² — low when confident

        // Update covariance and state for velocity states (indices 3-5)
        updateVelocityMeasurement(floatArrayOf(0f, 0f, 0f), R_zupt)

        // During long ZUPT, recalibrate biases
        if (zuptDetector.isInZupt() && zuptDetector.getZuptDurationMs() > 2000) {
            recalibrateBiases()
        }
    }

    /**
     * GPS velocity measurement update.
     */
    fun updateGps(fix: GpsFix) {
        if (!initialized) return

        // Use GPS speed as a scalar velocity constraint
        // GPS gives us ground speed; assume horizontal motion
        val gpsVelocity = floatArrayOf(fix.speed, 0f, 0f)  // simplified: use as x-direction speed
        val R_gps = fix.accuracy * fix.accuracy * 0.1f  // noise proportional to accuracy

        updateVelocityMeasurement(gpsVelocity, R_gps)
    }

    /**
     * Read current navigation state.
     */
    fun getState(): NavState {
        val speed = velocityIntegrator.getSpeed()
        val quatArray = orientation.toFloatArray()
        val speedKmh = speed * 3.6f

        // Compute confidence from trace of P (total uncertainty)
        val totalUncertainty = P[0][0] + P[1][1] + P[2][2] +
                P[3][3] + P[4][4] + P[5][5]
        val confidence = (1f / (1f + totalUncertainty)).coerceIn(0f, 1f)

        return NavState(
            timestampNs = lastTimestampNs,
            velocity = velocity.copyOf(),
            speed = speed,
            speedKmh = speedKmh,
            position = position.copyOf(),
            orientation = quatArray,
            accelBias = accelBias.copyOf(),
            gyroBias = gyroBias.copyOf(),
            isZupt = zuptDetector.isInZupt(),
            isUnderground = false,  // updated externally
            confidence = confidence
        )
    }

    /**
     * Get the sub-detectors for external wiring.
     */
    fun getZuptDetector(): ZUPTDetector = zuptDetector
    fun getOrientationEstimator(): OrientationEstimator = orientationEstimator
    fun getAccelerationTransformer(): AccelerationTransformer = accelTransformer
    fun getVelocityIntegrator(): VelocityIntegrator = velocityIntegrator

    // ===== Private EKF math =====

    /**
     * Simplified covariance propagation.
     * Uses a linearized model around the current state.
     */
    private fun propagateCovariance(dt: Float, navAccel: FloatArray, gyro: FloatArray) {
        // Build approximate state transition matrix F (15×15)
        // dp' = dp + dv*dt
        // dv' = dv - R*[a]x*dtheta*dt - R*dba*dt
        // dtheta' = dtheta - [w]x*dtheta*dt - dbg*dt
        // dba' = dba
        // dbg' = dbg

        val F = Array(15) { i -> FloatArray(15) { j -> if (i == j) 1f else 0f } }

        // dp/dv: position += velocity * dt
        for (i in 0..2) F[i][i + 3] = dt

        // dv/dtheta: velocity depends on orientation error
        // Approximate: cross-product term from nav accel
        val ax = navAccel[0]; val ay = navAccel[1]; val az = navAccel[2]
        // -R*[a]_x ≈ skew-symmetric of nav accel
        F[3][7] = az * dt;  F[3][8] = -ay * dt
        F[4][6] = -az * dt; F[4][8] = ax * dt
        F[5][6] = ay * dt;  F[5][7] = -ax * dt

        // dv/dba: velocity depends on accel bias
        for (i in 0..2) F[3 + i][9 + i] = -dt

        // dtheta/dbg: orientation depends on gyro bias
        for (i in 0..2) F[6 + i][12 + i] = -dt

        // P = F * P * F^T + Q * dt
        val FP = matMul(F, P)
        val FPFT = matMulTransB(FP, F)
        for (i in 0..14) {
            for (j in 0..14) {
                P[i][j] = FPFT[i][j] + Q[i][j] * dt
            }
        }

        // Symmetrize
        for (i in 0..14) {
            for (j in i + 1..14) {
                val avg = (P[i][j] + P[j][i]) / 2f
                P[i][j] = avg
                P[j][i] = avg
            }
        }
    }

    /**
     * Velocity measurement update (used for both ZUPT and GPS).
     * z = measured velocity (3-vector), R_diag = measurement noise per axis.
     */
    private fun updateVelocityMeasurement(z: FloatArray, R_diag: Float) {
        // H matrix: observes velocity states (indices 3-5 in error state)
        // H is 3×15, rows are unit vectors at positions 3,4,5

        // Innovation: y = z - H * x_predicted = z - velocity
        val y = floatArrayOf(z[0] - velocity[0], z[1] - velocity[1], z[2] - velocity[2])

        // S = H * P * H^T + R (3×3)
        // Since H picks out rows 3-5 of P, S = P[3:6, 3:6] + R*I
        val S = Array(3) { i -> FloatArray(3) { j -> P[3 + i][3 + j] } }
        S[0][0] += R_diag; S[1][1] += R_diag; S[2][2] += R_diag

        // K = P * H^T * S^{-1}
        // P * H^T = columns 3-5 of P (15×3)
        val PHt = Array(15) { i -> FloatArray(3) { j -> P[i][3 + j] } }
        val Sinv = invertSymmetric3(S)
        if (Sinv == null) return  // singular, skip

        val K = Array(15) { i ->
            FloatArray(3) { j ->
                PHt[i][0] * Sinv[0][j] + PHt[i][1] * Sinv[1][j] + PHt[i][2] * Sinv[2][j]
            }
        }

        // Update error state: dx = K * y
        // Apply to nominal state
        for (i in 0..2) {
            position[i] += K[i][0] * y[0] + K[i][1] * y[1] + K[i][2] * y[2]
        }
        for (i in 0..2) {
            velocity[i] += K[3 + i][0] * y[0] + K[3 + i][1] * y[1] + K[3 + i][2] * y[2]
        }

        // Orientation correction: apply rotation perturbation
        val dtheta = floatArrayOf(
            K[6][0] * y[0] + K[6][1] * y[1] + K[6][2] * y[2],
            K[7][0] * y[0] + K[7][1] * y[1] + K[7][2] * y[2],
            K[8][0] * y[0] + K[8][1] * y[1] + K[8][2] * y[2]
        )
        val dthetaNorm = sqrt(dtheta[0]*dtheta[0] + dtheta[1]*dtheta[1] + dtheta[2]*dtheta[2])
        if (dthetaNorm > 1e-10f) {
            val axis = floatArrayOf(dtheta[0]/dthetaNorm, dtheta[1]/dthetaNorm, dtheta[2]/dthetaNorm)
            val dq = Quaternion.fromAxisAngle(axis, dthetaNorm)
            orientation = (dq * orientation).normalize()
            orientationEstimator.setOrientation(orientation)
        }

        // Bias corrections
        for (i in 0..2) {
            accelBias[i] += K[9 + i][0] * y[0] + K[9 + i][1] * y[1] + K[9 + i][2] * y[2]
        }
        for (i in 0..2) {
            gyroBias[i] += K[12 + i][0] * y[0] + K[12 + i][1] * y[1] + K[12 + i][2] * y[2]
        }

        accelTransformer.setBias(accelBias)

        // Update velocity integrator
        velocityIntegrator.setVelocity(velocity)
        velocityIntegrator.setPosition(position)

        // Update covariance: P = (I - K*H) * P
        // KH is 15×15, only non-zero at columns 3-5
        val IminusKH = Array(15) { i -> FloatArray(15) { j -> if (i == j) 1f else 0f } }
        for (i in 0..14) {
            for (j in 0..2) {
                IminusKH[i][3 + j] -= K[i][j]
            }
        }
        val newP = matMul(IminusKH, P)
        for (i in 0..14) {
            for (j in 0..14) {
                P[i][j] = newP[i][j]
            }
        }

        // Symmetrize
        for (i in 0..14) {
            for (j in i + 1..14) {
                val avg = (P[i][j] + P[j][i]) / 2f
                P[i][j] = avg
                P[j][i] = avg
            }
        }
    }

    /** Recalibrate biases during long ZUPT. */
    private fun recalibrateBiases() {
        // During ZUPT, velocity should be zero → accel reads only gravity → bias = accel - g
        // Gyro should read zero → bias = gyro reading
        // The EKF naturally estimates this via the state, but we force it during long stops
        val accelMean = zuptDetector.computeMean(emptyList())  // needs actual buffer access
        // For now, just rely on EKF convergence — bias states are observable during ZUPT
    }

    fun reset() {
        position = floatArrayOf(0f, 0f, 0f)
        velocity = floatArrayOf(0f, 0f, 0f)
        orientation = Quaternion.IDENTITY
        accelBias = floatArrayOf(0f, 0f, 0f)
        gyroBias = floatArrayOf(0f, 0f, 0f)
        lastTimestampNs = 0L
        initialized = false
        initializeCovariance()
        orientationEstimator.reset()
        accelTransformer.reset()
        velocityIntegrator.reset()
        zuptDetector.reset()
    }

    // ===== Matrix utility functions =====

    /** 15×15 matrix multiply: A * B */
    private fun matMul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val result = Array(15) { FloatArray(15) }
        for (i in 0..14) {
            for (j in 0..14) {
                var sum = 0f
                for (k in 0..14) sum += a[i][k] * b[k][j]
                result[i][j] = sum
            }
        }
        return result
    }

    /** 15×15 * 15×15^T: A * B^T */
    private fun matMulTransB(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val result = Array(15) { FloatArray(15) }
        for (i in 0..14) {
            for (j in 0..14) {
                var sum = 0f
                for (k in 0..14) sum += a[i][k] * b[j][k]
                result[i][j] = sum
            }
        }
        return result
    }

    /** Invert a symmetric 3×3 matrix. Returns null if singular. */
    private fun invertSymmetric3(m: Array<FloatArray>): Array<FloatArray>? {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][1]; val e = m[1][2]; val f = m[2][2]

        val det = a*(d*f - e*e) - b*(b*f - c*e) + c*(b*e - c*d)
        if (kotlin.math.abs(det) < 1e-15f) return null
        val invDet = 1f / det

        return arrayOf(
            floatArrayOf((d*f - e*e)*invDet, (c*e - b*f)*invDet, (b*e - c*d)*invDet),
            floatArrayOf((c*e - b*f)*invDet, (a*f - c*c)*invDet, (b*c - a*e)*invDet),
            floatArrayOf((b*e - c*d)*invDet, (b*c - a*e)*invDet, (a*d - b*b)*invDet)
        )
    }
}
