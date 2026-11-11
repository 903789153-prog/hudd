package com.sys.hud

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 一个可绘制的敌方单位 */
data class DrawUnit(
    val id: Int,
    val camp: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val hp: Float,
    val hpMax: Float,
    val visible: Boolean
)

/**
 * 抓包线程写、绘制线程读的共享状态。
 * 双缓冲 + 时间戳，超过 MAX_AGE_MS 的数据自动失效（防止显示过期点位）。
 */
object EspState {
    private const val MAX_AGE_MS = 400L

    @Volatile var screenW = 1080
    @Volatile var screenH = 2400

    /** 本地玩家 */
    @Volatile var selfId = -1
    @Volatile var selfX = 0f
    @Volatile var selfZ = 0f

    /** 相机是否可用（决定画 3D 框还是只画雷达） */
    @Volatile var cameraOk = false
    val vp = FloatArray(16)

    /** 雷达显示半径（世界单位） */
    @Volatile var radarRange = 3200f

    /** 抓到的包计数，用来判断链路是否通 */
    @Volatile var pktCount = 0L
    @Volatile var unitUpdates = 0L

    private val lock = ReentrantLock()
    private val units = ArrayList<DrawUnit>(32)
    @Volatile private var stampMs = 0L

    fun putUnits(list: List<DrawUnit>) = lock.withLock {
        units.clear()
        units.addAll(list)
        stampMs = System.currentTimeMillis()
        unitUpdates++
    }

    fun putCamera(m: FloatArray) {
        if (m.size < 16) return
        System.arraycopy(m, 0, vp, 0, 16)
        cameraOk = true
    }

    fun snapshot(): List<DrawUnit> = lock.withLock {
        if (System.currentTimeMillis() - stampMs > MAX_AGE_MS) ArrayList(0)
        else ArrayList(units)
    }

    fun setSelf(id: Int, x: Float, z: Float) {
        selfId = id; selfX = x; selfZ = z
    }

    fun reset() = lock.withLock {
        units.clear()
        stampMs = 0
        cameraOk = false
    }
}
