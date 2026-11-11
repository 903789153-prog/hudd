package com.sys.hud

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_VPN = 1001

    private lateinit var status: TextView
    private lateinit var pkgInput: EditText
    private var vpnRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshStatus()
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20), d(36), d(20), d(20))
            setBackgroundColor(Color.rgb(250, 250, 252))
        }

        root.addView(title("SystemEnhance"))
        root.addView(sub("网络层分析 + 覆盖层标注"))

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, d(14), 0, d(14))
            setTextColor(Color.rgb(60, 60, 60))
        }
        root.addView(status)

        root.addView(label("目标应用包名"))
        pkgInput = EditText(this).apply {
            setText(Config.targetPkg)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
        root.addView(pkgInput)

        root.addView(label("自身阵营值（敌方 = 不等于该值）"))
        val campInput = EditText(this).apply {
            setText(Config.selfCamp.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
        }
        root.addView(campInput)

        root.addView(switchRow("屏幕空间标框", Config.enableEsp) { Config.enableEsp = it })
        root.addView(switchRow("圆形雷达", Config.enableRadar) { Config.enableRadar = it })
        root.addView(switchRow("雷达显示血量", Config.showHp) { Config.showHp = it })
        root.addView(switchRow("原始包落盘（离线分析用）", Config.dumpRaw) { Config.dumpRaw = it })
        root.addView(switchRow("解析调试日志", Config.verbose) { Config.verbose = it })

        root.addView(label("分片头长度（headerLen）"))
        val hdrInput = EditText(this).apply {
            setText(Config.headerLen.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
        }
        root.addView(hdrInput)

        root.addView(gap(10))

        val bAcc = Button(this).apply {
            text = "① 开启覆盖层服务（无障碍）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(bAcc)

        val bVpn = Button(this).apply {
            text = "② 启动抓包"
            setOnClickListener {
                Config.targetPkg = pkgInput.text.toString().trim().ifBlank { "com.tencent.tmgp.sgame" }
                Config.selfCamp = campInput.text.toString().toIntOrNull() ?: 1
                Config.headerLen = hdrInput.text.toString().toIntOrNull() ?: 0
                ProtoParser.headerLen = Config.headerLen
                ProtoParser.debugLog = Config.verbose
                prepareAndStart()
            }
        }
        root.addView(bVpn)

        val bStop = Button(this).apply {
            text = "③ 停止抓包"
            setOnClickListener { stopVpn() }
        }
        root.addView(bStop)

        root.addView(gap(14))
        root.addView(sub(
            "提示：抓包只影响上面填写的那个应用，其余应用网络不受影响。\n" +
            "覆盖层走无障碍层，普通应用无法枚举到该窗口。\n" +
            "未接解析算法时，包会落在 Android/data/" + packageName + "/files/dumps/ 下。"
        ))

        val sc = ScrollView(this)
        sc.addView(root)
        return sc
    }

    private fun prepareAndStart() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN)
        } else {
            startVpn()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpn()
            else toast("未授权 VPN，抓包无法启动")
        }
    }

    private fun startVpn() {
        val i = Intent(this, TapVpnService::class.java).apply {
            action = TapVpnService.ACTION_START
            putExtra(TapVpnService.EXTRA_PKG, Config.targetPkg)
            putExtra(TapVpnService.EXTRA_DUMP, Config.dumpRaw)
        }
        startForegroundService(i)
        vpnRunning = true
        refreshStatus()
        toast("已启动")
    }

    private fun stopVpn() {
        val i = Intent(this, TapVpnService::class.java).apply { action = TapVpnService.ACTION_STOP }
        startService(i)
        vpnRunning = false
        refreshStatus()
    }

    private fun refreshStatus() {
        if (isFinishing || isDestroyed) return
        status.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val ov = if (EspOverlayService.alive) "已连接" else "未连接"
            status.text = "覆盖层服务：$ov\n" +
                "抓包：${if (vpnRunning) "运行中" else "已停止"}\n" +
                "已收包：${EspState.pktCount}　单位更新：${EspState.unitUpdates}\n" +
                "相机矩阵：${if (EspState.cameraOk) "已获取" else "无"}"
            refreshStatus()
        }, 800)
    }

    // ── tiny ui helpers ───────────────────────────────────────
    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 22f; setTextColor(Color.rgb(20, 20, 20))
        setPadding(0, 0, 0, d(4))
    }

    private fun sub(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Color.rgb(120, 120, 120))
        setPadding(0, 0, 0, d(6))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.rgb(90, 90, 90))
        setPadding(0, d(12), 0, d(2))
    }

    private fun gap(h: Int): android.view.View {
        val v = android.view.View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, d(h))
        return v
    }

    private fun switchRow(text: String, init: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(10), 0, d(2))
        }
        val tv = TextView(this).apply { this.text = text; textSize = 14f }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = init
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        row.addView(sw)
        return row
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
