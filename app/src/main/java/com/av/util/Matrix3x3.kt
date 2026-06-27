package com.av.util

/**
 * 3×3 matrix stored as flat FloatArray[9] in row-major order.
 * Indices: [0,1,2; 3,4,5; 6,7,8]
 */
object Matrix3x3 {

    val IDENTITY = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    /** Matrix-vector multiplication: result = M * v */
    fun multiplyVec(m: FloatArray, v: FloatArray): FloatArray {
        require(m.size >= 9 && v.size >= 3)
        return floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        )
    }

    /** Matrix multiplication: result = a * b */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size >= 9 && b.size >= 9)
        return FloatArray(9) { i ->
            val row = i / 3
            val col = i % 3
            a[row * 3 + 0] * b[0 * 3 + col] +
                    a[row * 3 + 1] * b[1 * 3 + col] +
                    a[row * 3 + 2] * b[2 * 3 + col]
        }
    }

    /** Transpose: result = M^T */
    fun transpose(m: FloatArray): FloatArray {
        require(m.size >= 9)
        return floatArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8]
        )
    }

    /** Add two matrices */
    fun add(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size >= 9 && b.size >= 9)
        return FloatArray(9) { a[it] + b[it] }
    }

    /** Subtract: a - b */
    fun subtract(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size >= 9 && b.size >= 9)
        return FloatArray(9) { a[it] - b[it] }
    }

    /** Scalar multiply */
    fun scale(m: FloatArray, s: Float): FloatArray {
        return FloatArray(9) { m[it] * s }
    }

    /** Outer product of two 3-vectors: result = a * b^T (3x3 matrix) */
    fun outerProduct(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size >= 3 && b.size >= 3)
        return FloatArray(9) { i ->
            val row = i / 3
            val col = i % 3
            a[row] * b[col]
        }
    }
}
