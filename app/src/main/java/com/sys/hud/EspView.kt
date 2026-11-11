package com.sys.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HUD 绘制。
 *
 * 双模：
 *   RADAR  —— 不需要相机，只按世界坐标画相对方位（右下角圆形雷达）
 *   ESP3D  —— 有相机 VP 矩阵时画屏幕空间方框 + 血条 + 距离
 *
 * 纯 Canvas，无 GL、无第三方依赖、每帧重绘约 20 个点，功耗可忽略。
 */
class EspView(ctx: Context) : View(ctx) {

    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }
    private val selfPath = Path()

    private val enemyColor = Color.rgb(255, 66, 52)
    private val selfColor = Color.rgb(64, 220, 120)

    private val radarR = 210f      // 雷达半径 px（在 onSizeChanged 里按 dp 修正）
    private var density = 1f

    init {
        density = resources.displayMetrics.density
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        EspState.screenW = w
        EspState.screenH = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val units = EspState.snapshot()

        if (Config.enableEsp && EspState.cameraOk) draw3D(canvas, units)
        if (Config.enableRadar) drawRadar(canvas, units)

        postInvalidateOnAnimation()
    }

    // ── 3D 屏幕空间 ────────────────────────────────────────────
    private fun draw3D(c: Canvas, units: List<DrawUnit>) {
        val sw = width.toFloat()
        val sh = height.toFloat()
        if (sw <= 0f || sh <= 0f) return
        val vp = EspState.vp

        for (u in units) {
            if (u.camp == Config.selfCamp) continue
            val sp = MatrixMath.worldToScreen(vp, u.x, u.y, u.z, sw.toInt(), sh.toInt()) ?: continue
            val (px, py) = sp

            // 距离（世界单位近似，用于高度缩放）
            val dx = u.x - EspState.selfX
            val dz = u.z - EspState.selfZ
            val dist = sqrt(dx * dx + dz * dz)
            if (dist > Config.maxDist) continue

            val h = (2400f / (dist + 1f)).coerceIn(30f, 420f)
            val w = h * 0.42f

            // 框
            pStroke.color = enemyColor
            pStroke.strokeWidth = 3f * density
            c.drawRect(px - w, py - h, px + w, py, pStroke)

            // 血条
            val ratio = (u.hp / u.hpMax).coerceIn(0f, 1f)
            pFill.color = Color.argb(170, 0, 0, 0)
            c.drawRect(px - w, py - h - 10f * density, px + w, py - h - 3f * density, pFill)
            pFill.color = enemyColor
            c.drawRect(px - w, py - h - 10f * density, px - w + 2 * w * ratio, py - h - 3f * density, pFill)

            // 脚下连线
            pStroke.color = Color.argb(160, 255, 255, 255)
            c.drawLine(px, py, px, py + h * 0.12f, pStroke)

            // 距离文字
            pText.color = Color.WHITE
            c.drawText("${dist.toInt()}", px - w, py + 26f * density, pText)
        }
    }

    // ── 圆形雷达 ───────────────────────────────────────────────
    private fun drawRadar(c: Canvas, units: List<DrawUnit>) {
        val r = radarR * density
        val cx = width - r - 40f * density
        val cy = height - r - 120f * density

        // 底盘
        pFill.color = Color.argb(90, 0, 0, 0)
        c.drawCircle(cx, cy, r, pFill)

        // 同心环
        pStroke.color = Color.argb(110, 255, 255, 255)
        pStroke.strokeWidth = 1.5f * density
        c.drawCircle(cx, cy, r, pStroke)
        c.drawCircle(cx, cy, r * 0.66f, pStroke)
        c.drawCircle(cx, cy, r * 0.33f, pStroke)

        // 十字
        c.drawLine(cx - r, cy, cx + r, cy, pStroke)
        c.drawLine(cx, cy - r, cx, cy + r, pStroke)

        val range = EspState.radarRange
        val k = r / range

        // 自己
        pFill.color = selfColor
        c.drawCircle(cx, cy, 7f * density, pFill)

        for (u in units) {
            if (u.camp == Config.selfCamp) continue
            var dx = u.x - EspState.selfX
            var dz = u.z - EspState.selfZ
            // 雷达以"屏幕上方 = 世界 +Z"为基准
            var rx = dx * k
            var ry = -dz * k
            val d = sqrt(rx * rx + ry * ry)
            val clipped = d > r
            if (clipped) {
                val s = r / d
                rx *= s; ry *= s
            }
            val ax = cx + rx
            val ay = cy + ry

            pFill.color = if (clipped) Color.argb(120, 255, 66, 52) else enemyColor
            c.drawCircle(ax, ay, 6f * density, pFill)

            if (!clipped && Config.showHp) {
                val ratio = (u.hp / u.hpMax).coerceIn(0f, 1f)
                pFill.color = Color.argb(200, 0, 0, 0)
                c.drawRect(ax - 14f * density, ay - 16f * density,
                    ax + 14f * density, ay - 11f * density, pFill)
                pFill.color = enemyColor
                c.drawRect(ax - 14f * density, ay - 16f * density,
                    ax - 14f * density + 28f * density * ratio, ay - 11f * density, pFill)
            }
        }

        // 状态行
        pText.color = Color.argb(200, 200, 255, 200)
        val st = if (EspState.pktCount == 0L) "no traffic"
        else "pkt ${EspState.pktCount} u ${EspState.unitUpdates}"
        c.drawText(st, cx - r, cy + r + 28f * density, pText)
    }
}
