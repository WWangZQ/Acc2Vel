package com.av.util

import kotlin.math.sqrt

/**
 * Unit quaternion [w, x, y, z] for 3D rotation.
 * Convention: q = w + xi + yj + zk, |q| = 1.
 * Rotation: v' = q * v * q⁻¹
 */
data class Quaternion(
    val w: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    /** Hamilton product: this * other */
    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w
        )
    }

    /** Conjugate q* = [w, -x, -y, -z] */
    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    /** Norm |q| */
    fun norm(): Float = sqrt(w * w + x * x + y * y + z * z)

    /** Normalized quaternion (unit quaternion) */
    fun normalize(): Quaternion {
        val n = norm()
        return if (n > 1e-10f) Quaternion(w / n, x / n, y / n, z / n)
        else IDENTITY
    }

    /**
     * Rotate a 3D vector v by this quaternion.
     * Result = q * [0, v] * q⁻¹
     */
    fun rotate(v: FloatArray): FloatArray {
        require(v.size >= 3)
        val qv = Quaternion(0f, v[0], v[1], v[2])
        val result = this * qv * this.conjugate()
        return floatArrayOf(result.x, result.y, result.z)
    }

    /**
     * Convert to 3x3 rotation matrix (body → nav frame).
     * Row-major: [r00, r01, r02, r10, r11, r12, r20, r21, r22]
     */
    fun toRotationMatrix(): FloatArray {
        val q = normalize()
        val ww = q.w * q.w
        val xx = q.x * q.x
        val yy = q.y * q.y
        val zz = q.z * q.z
        val wx = q.w * q.x
        val wy = q.w * q.y
        val wz = q.w * q.z
        val xy = q.x * q.y
        val xz = q.x * q.z
        val yz = q.y * q.z

        return floatArrayOf(
            ww + xx - yy - zz,  2f * (xy - wz),      2f * (xz + wy),
            2f * (xy + wz),      ww - xx + yy - zz,   2f * (yz - wx),
            2f * (xz - wy),      2f * (yz + wx),       ww - xx - yy + zz
        )
    }

    /**
     * Convert to roll/pitch/yaw (Euler angles) in radians.
     * Roll (X), Pitch (Y), Yaw (Z) — Tait-Bryan ZYX convention.
     */
    fun toEulerAngles(): FloatArray {
        val q = normalize()
        // Roll (x-axis rotation)
        val sinr = 2f * (q.w * q.x + q.y * q.z)
        val cosr = 1f - 2f * (q.x * q.x + q.y * q.y)
        val roll = kotlin.math.atan2(sinr, cosr)

        // Pitch (y-axis rotation)
        val sinp = 2f * (q.w * q.y - q.z * q.x)
        val pitch = if (kotlin.math.abs(sinp) >= 1f)
            (if (sinp > 0) kotlin.math.PI.toFloat() / 2f else -kotlin.math.PI.toFloat() / 2f)
        else kotlin.math.asin(sinp)

        // Yaw (z-axis rotation)
        val siny = 2f * (q.w * q.z + q.x * q.y)
        val cosy = 1f - 2f * (q.y * q.y + q.z * q.z)
        val yaw = kotlin.math.atan2(siny, cosy)

        return floatArrayOf(roll, pitch, yaw)
    }

    /** Convert to FloatArray [w, x, y, z] */
    fun toFloatArray(): FloatArray = floatArrayOf(w, x, y, z)

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /** Create from FloatArray [w, x, y, z] */
        fun fromFloatArray(a: FloatArray): Quaternion {
            require(a.size >= 4)
            return Quaternion(a[0], a[1], a[2], a[3])
        }

        /**
         * Create quaternion from axis-angle representation.
         * @param axis unit vector [x, y, z]
         * @param angleRad rotation angle in radians
         */
        fun fromAxisAngle(axis: FloatArray, angleRad: Float): Quaternion {
            val halfAngle = angleRad / 2f
            val s = kotlin.math.sin(halfAngle)
            return Quaternion(
                w = kotlin.math.cos(halfAngle),
                x = axis[0] * s,
                y = axis[1] * s,
                z = axis[2] * s
            )
        }

        /**
         * Spherical linear interpolation between two quaternions.
         */
        fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
            var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            var bAdj = b
            if (dot < 0f) {
                dot = -dot
                bAdj = Quaternion(-b.w, -b.x, -b.y, -b.z)
            }
            if (dot > 0.9995f) {
                // Linear fallback for nearly parallel quaternions
                return Quaternion(
                    a.w + t * (bAdj.w - a.w),
                    a.x + t * (bAdj.x - a.x),
                    a.y + t * (bAdj.y - a.y),
                    a.z + t * (bAdj.z - a.z)
                ).normalize()
            }
            val theta0 = kotlin.math.acos(dot)
            val theta = theta0 * t
            val sinTheta = kotlin.math.sin(theta)
            val sinTheta0 = kotlin.math.sin(theta0)
            val s0 = kotlin.math.cos(theta) - dot * sinTheta / sinTheta0
            val s1 = sinTheta / sinTheta0
            return Quaternion(
                s0 * a.w + s1 * bAdj.w,
                s0 * a.x + s1 * bAdj.x,
                s0 * a.y + s1 * bAdj.y,
                s0 * a.z + s1 * bAdj.z
            )
        }
    }
}
