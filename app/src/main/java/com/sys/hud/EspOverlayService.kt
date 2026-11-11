package com.sys.hud

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 用无障碍覆盖层承载 HUD。
 *
 * 为什么不用 TYPE_APPLICATION_OVERLAY：
 *   无障碍覆盖层的窗口挂在无障碍服务的 token 上，普通应用通过
 *   WindowManager / AccessibilityService 都列举不到别的应用的无障碍层，
 *   而 APPLICATION_OVERLAY 会被部分反作弊按 uid 枚举出来。
 */
class EspOverlayService : AccessibilityService() {

    companion object {
        private const val TAG = "hudov"
        @Volatile var alive = false
    }

    private var wm: WindowManager? = null
    private var view: EspView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // 明确不参与输入、不抢焦点、不进最近任务
        }

        val v = EspView(this)
        try {
            wm?.addView(v, lp)
            view = v
            Log.i(TAG, "overlay added")
        } catch (e: Throwable) {
            Log.e(TAG, "addView failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只用来感知前台切换，绘制逻辑不依赖事件
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        alive = false
        remove()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        alive = false
        remove()
        super.onDestroy()
    }

    private fun remove() {
        view?.let { v -> try { wm?.removeView(v) } catch (_: Throwable) {} }
        view = null
    }
}
