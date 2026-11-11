package com.sys.hud

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 列主序 4x4（Unity 约定）。m[col*4 + row]
 */
object MatrixMath {

    /** 透视投影 */
    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = (1.0 / tan(Math.toRadians(fovYDeg.toDouble()) / 2.0)).toFloat()
        val m = FloatArray(16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    /** 由 yaw/pitch/eye 构造 view 矩阵（Unity 左手系） */
    fun lookAt(yawDeg: Float, pitchDeg: Float, eyeX: Float, eyeY: Float, eyeZ: Float): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        // forward
        val fx = (sin(yaw) * cos(pitch)).toFloat()
        val fy = (-sin(pitch)).toFloat()
        val fz = (cos(yaw) * cos(pitch)).toFloat()
        // right = worldUp x forward 归一化（左手系取反）
        var rx = 1f * fz - 0f * fy
        var ry = 0f * fx - 0f * fz
        var rz = 0f * fy - 1f * fx
        val rl = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz).let { if (it < 1e-6f) 1f else it }
        rx /= rl; ry /= rl; rz /= rl
        // up = forward x right
        val ux = fy * rz - fz * ry
        val uy = fz * rx - fx * rz
        val uz = fx * ry - fy * rx

        val m = FloatArray(16)
        m[0] = rx; m[4] = ry; m[8] = rz
        m[1] = ux; m[5] = uy; m[9] = uz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[12] = -(rx * eyeX + ry * eyeY + rz * eyeZ)
        m[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        m[14] = (fx * eyeX + fy * eyeY + fz * eyeZ)
        m[15] = 1f
        return m
    }

    /** out = a * b（列主序） */
    fun mul(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
        return out
    }

    /**
     * 世界坐标 → 屏幕坐标。返回 null 表示在相机背后或超出屏幕。
     */
    fun worldToScreen(
        vp: FloatArray, x: Float, y: Float, z: Float,
        sw: Int, sh: Int
    ): Pair<Float, Float>? {
        val cx = x * vp[0] + y * vp[4] + z * vp[8] + vp[12]
        val cy = x * vp[1] + y * vp[5] + z * vp[9] + vp[13]
        val cw = x * vp[3] + y * vp[7] + z * vp[11] + vp[15]
        if (cw < 0.01f) return null
        val inv = 1f / cw
        val sx = (cx * inv * 0.5f + 0.5f) * sw
        val sy = (1f - (cy * inv * 0.5f + 0.5f)) * sh
        if (sx < -400f || sy < -400f || sx > sw + 400f || sy > sh + 400f) return null
        return sx to sy
    }
}
