package com.sys.hud

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 对局包解析器。
 *
 * 三个可调点（按你自己的逆向结果填）：
 *   1) headerLen —— 分片头长度
 *   2) cipher    —— 解密实现（见 Cipher 接口，内置三种常见形态）
 *   3) Op 里的 opcode 表
 *
 * 没接 cipher 时会走 maybeDump()，把原始 payload 落盘，方便先看清结构再定 opcode。
 */
object ProtoParser {
    private const val TAG = "hudp"

    @Volatile var headerLen = 0
    @Volatile var cipher: Cipher? = null
    @Volatile var dumpDir: File? = null
    @Volatile var debugLog = false

    private var dumped = 0

    interface Cipher {
        fun decrypt(src: ByteArray, off: Int, len: Int): ByteArray?
    }

    /** 内置：滚动 XOR（密钥表 + 序号线） */
    class XorCipher(private val key: ByteArray) : Cipher {
        private var seq = 0
        override fun decrypt(src: ByteArray, off: Int, len: Int): ByteArray? {
            if (len <= 0) return null
            val out = ByteArray(len)
            for (i in 0 until len) {
                out[i] = (src[off + i].toInt() xor key[(i + seq) % key.size].toInt()).toByte()
            }
            seq++
            return out
        }
    }

    /** 内置：XXTEA（16 字节以上，长度 4 对齐） */
    class XxteaCipher(private val k: IntArray) : Cipher {
        override fun decrypt(src: ByteArray, off: Int, len: Int): ByteArray? {
            if (len < 8 || len % 4 != 0) return null
            val n = len / 4
            val v = IntArray(n)
            val bb = ByteBuffer.wrap(src, off, len).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) v[i] = bb.int
            xxteaDecrypt(v, k)
            val out = ByteArray(len)
            val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) ob.putInt(v[i])
            val real = v[n - 1]
            if (real in 1..len) return out.copyOf(real)
            return out
        }

        private fun xxteaDecrypt(v: IntArray, k: IntArray) {
            val n = v.size
            if (n < 2) return
            var y: Int
            val q = 6 + 52 / n
            var d = q * -0x61c88647
            var sum = d
            var z = v[n - 1]
            var e: Int
            var p: Int
            while (sum != 0) {
                e = (sum ushr 2) and 3
                p = n - 1
                while (p > 0) {
                    z = v[p - 1]
                    y = v[p] - (((z ushr 5 xor y shl 2) + (y ushr 3 xor z shl 4)) xor ((sum xor y) + (k[(p and 3) xor e] xor z)))
                    v[p] = y
                    p--
                }
                z = v[n - 1]
                y = v[0] - (((z ushr 5 xor y shl 2) + (y ushr 3 xor z shl 4)) xor ((sum xor y) + (k[(p and 3) xor e] xor z)))
                v[0] = y
                sum -= 0x61c88647
            }
        }
    }

    /** 内置：单字节 XOR（调试用） */
    class SimpleXor(private val b: Int) : Cipher {
        override fun decrypt(src: ByteArray, off: Int, len: Int): ByteArray? {
            val out = ByteArray(len)
            for (i in 0 until len) out[i] = (src[off + i].toInt() xor b).toByte()
            return out
        }
    }

    // ── opcode 表（逆向确认后改这里）─────────────────────────────
    object Op {
        const val UNIT = 0x21
        const val CAMERA = 0x22
        const val SELF = 0x23
    }

    private const val UNIT_REC = 27

    fun feed(dg: ByteArray) {
        EspState.pktCount++
        if (dg.size < headerLen + 3) { maybeDump(dg); return }
        val c = cipher
        if (c == null) { maybeDump(dg); return }
        val body = c.decrypt(dg, headerLen, dg.size - headerLen)
        if (body == null) { maybeDump(dg); return }
        walk(body)
    }

    private fun walk(b: ByteArray) {
        var p = 0
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        while (p + 3 <= b.size) {
            val op = bb.get(p).toInt() and 0xFF
            val ln = bb.getShort(p + 1).toInt() and 0xFFFF
            if (p + 3 + ln > b.size) break
            when (op) {
                Op.UNIT -> parseUnits(bb, p + 3, ln)
                Op.CAMERA -> parseCamera(bb, p + 3, ln)
                Op.SELF -> parseSelf(bb, p + 3, ln)
            }
            p += 3 + ln
        }
    }

    /** 单位快照：[count u16] + count × 27B */
    private fun parseUnits(bb: ByteBuffer, off: Int, len: Int) {
        if (len < 2) return
        val count = bb.getShort(off).toInt() and 0xFFFF
        if (count <= 0 || count > 64) return
        if (len < 2 + count * UNIT_REC) return
        val out = ArrayList<DrawUnit>(count)
        for (i in 0 until count) {
            val o = off + 2 + i * UNIT_REC
            val id = bb.getInt(o)
            val camp = bb.get(o + 4).toInt() and 0xFF
            val vis = (bb.get(o + 5).toInt() and 0xFF) != 0
            val hp = bb.getFloat(o + 6)
            val hpMax = bb.getFloat(o + 10)
            val x = bb.getFloat(o + 14)
            val y = bb.getFloat(o + 18)
            val z = bb.getFloat(o + 22)
            if (hpMax <= 0f || hp <= 0f) continue
            if (id == EspState.selfId) { EspState.setSelf(id, x, z); continue }
            out.add(DrawUnit(id, camp, x, y, z, hp, hpMax, vis))
        }
        EspState.putUnits(out)
        if (debugLog) Log.d(TAG, "units=${out.size}")
    }

    /** 相机：len>=64 视为直接给 16 个 float（VP 矩阵）；否则按 yaw/pitch/fov/eye 构造 */
    private fun parseCamera(bb: ByteBuffer, off: Int, len: Int) {
        if (len >= 64) {
            val m = FloatArray(16)
            for (i in 0 until 16) m[i] = bb.getFloat(off + i * 4)
            EspState.putCamera(m)
            return
        }
        if (len < 24) return
        val yaw = bb.getFloat(off)
        val pitch = bb.getFloat(off + 4)
        val fov = bb.getFloat(off + 8)
        val ex = bb.getFloat(off + 12)
        val ey = bb.getFloat(off + 16)
        val ez = bb.getFloat(off + 20)
        val aspect = if (EspState.screenH > 0) EspState.screenW.toFloat() / EspState.screenH else 0.46f
        val v = MatrixMath.lookAt(yaw, pitch, ex, ey, ez)
        val p = MatrixMath.perspective(fov, aspect, 0.3f, 6000f)
        EspState.putCamera(MatrixMath.mul(p, v))
    }

    /** 本地玩家：[id u32][x f32][y f32][z f32] */
    private fun parseSelf(bb: ByteBuffer, off: Int, len: Int) {
        if (len < 16) return
        val id = bb.getInt(off)
        val x = bb.getFloat(off + 4)
        val z = bb.getFloat(off + 12)
        EspState.setSelf(id, x, z)
    }

    /** 未解密时把 payload 落盘，方便离线看结构 */
    private fun maybeDump(dg: ByteArray) {
        val dir = dumpDir ?: return
        if (dumped >= 400) return
        try {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "p%04d_%d.bin".format(dumped++, dg.size))
            FileOutputStream(f).use { it.write(dg) }
        } catch (_: Throwable) {}
    }
}
