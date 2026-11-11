package com.sys.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * 只抓目标游戏的 UDP 对局流量，其余应用走正常网络。
 *
 * 数据流：
 *   tun read → 解析 IPv4/UDP → 交给 ProtoParser → 用户态 DatagramSocket 转发
 *   回程 socket → 自己造 IP/UDP 头 → 写回 tun
 *
 * 不需要 root。检测面：仅一个 VpnService 授权，进程里没有任何游戏内痕迹。
 */
class TapVpnService : VpnService() {

    companion object {
        private const val TAG = "hudv"
        private const val CH_ID = "hud"
        private const val CH_NAME = "Network helper"
        private const val NOTI_ID = 1001

        const val ACTION_START = "com.sys.hud.START"
        const val ACTION_STOP = "com.sys.hud.STOP"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_DUMP = "dump"

        const val TUN_ADDR = "10.66.0.1"
    }

    @Volatile private var running = false
    private var pfd: ParcelFileDescriptor? = null
    private val socks = ConcurrentHashMap<Int, DatagramSocket>()
    private val backThreads = ConcurrentHashMap<Int, Thread>()
    @Volatile private var outS: FileOutputStream? = null
    private val outLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> {
                val pkg = intent?.getStringExtra(EXTRA_PKG) ?: "com.tencent.tmgp.sgame"
                val dump = intent?.getBooleanExtra(EXTRA_DUMP, false) ?: false
                if (!running) start(pkg, dump)
            }
        }
        return START_STICKY
    }

    private fun start(pkg: String, dump: Boolean) {
        startForeground(NOTI_ID, buildNotification())

        val b = Builder()
            .addAddress(TUN_ADDR, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("223.5.5.5")
            .setMtu(1500)
            .setSession("net")
            .setBlocking(true)

        try {
            b.addAllowedApplication(pkg)
        } catch (e: Throwable) {
            Log.w(TAG, "addAllowedApplication 失败，改为全局：${e.message}")
        }

        val p = try { b.establish() } catch (e: Throwable) { Log.e(TAG, "establish: ${e.message}"); null }
        if (p == null) { stopSelf(); return }
        pfd = p
        running = true

        if (dump) {
            ProtoParser.dumpDir = getExternalFilesDir("dumps")
        }

        val inS = FileInputStream(p.fileDescriptor)
        outS = FileOutputStream(p.fileDescriptor)

        thread(name = "tun-pump", priority = Thread.MAX_PRIORITY) { pump(inS) }
        thread(name = "sock-reap") {
            while (running) {
                Thread.sleep(5000)
                reapIdle()
            }
        }
        Log.i(TAG, "vpn started pkg=$pkg dump=$dump")
    }

    private fun pump(inS: FileInputStream) {
        val buf = ByteArray(65536)
        while (running) {
            val n = try { inS.read(buf) } catch (e: Throwable) { break }
            if (n <= 0) continue
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if ((buf[0].toInt() and 0xF0) != 0x40) continue   // 只处理 IPv4
            if (n < ihl + 8) continue
            if ((buf[9].toInt() and 0xFF) != 17) continue      // 只处理 UDP

            if ((buf[ihl + 2].toInt() and 0xFF) == 0x35) continue // 跳过 DNS(53) 以免干扰

            val dstIp = ip(buf, 16)
            val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
            val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
            val udpLen = ((buf[ihl + 4].toInt() and 0xFF) shl 8) or (buf[ihl + 5].toInt() and 0xFF)
            if (udpLen < 8 || ihl + udpLen > n) continue

            val payload = buf.copyOfRange(ihl + 8, ihl + udpLen)

            // 1) 先给解析器
            try { ProtoParser.feed(payload) } catch (_: Throwable) {}

            // 2) 转发
            val sock = ensureSocket(srcPort)
            try {
                sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(dstIp), dstPort))
                seen[srcPort] = System.currentTimeMillis()
            } catch (_: Throwable) {}
        }
    }

    private val seen = ConcurrentHashMap<Int, Long>()

    private fun ensureSocket(srcPort: Int): DatagramSocket {
        socks[srcPort]?.let { return it }
        val s = DatagramSocket()
        protect(s)
        socks[srcPort] = s
        seen[srcPort] = System.currentTimeMillis()

        val t = thread(name = "back-$srcPort", isDaemon = true) {
            val rcv = ByteArray(65536)
            while (running && !s.isClosed) {
                try {
                    val p = DatagramPacket(rcv, rcv.size)
                    s.soTimeout = 800
                    s.receive(p)
                    val srcIp = p.address.hostAddress ?: continue
                    val pkt = buildIpv4Udp(srcIp, p.port, TUN_ADDR, srcPort, p.data.copyOf(p.length))
                    synchronized(outLock) { outS?.write(pkt) }
                } catch (_: Throwable) {}
            }
        }
        backThreads[srcPort] = t
        return s
    }

    private fun reapIdle() {
        val now = System.currentTimeMillis()
        for ((port, ts) in seen) {
            if (now - ts > 60_000) {
                socks.remove(port)?.close()
                backThreads.remove(port)?.interrupt()
                seen.remove(port)
            }
        }
    }

    private fun ip(b: ByteArray, o: Int) =
        "${b[o].toInt() and 0xFF}.${b[o + 1].toInt() and 0xFF}.${b[o + 2].toInt() and 0xFF}.${b[o + 3].toInt() and 0xFF}"

    private fun buildIpv4Udp(
        srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        bb.put(0x45); bb.put(0); bb.putShort(total.toShort())
        bb.putShort(0); bb.putShort(0); bb.put(0x40.toByte()); bb.put(17)
        bb.putShort(0)
        bb.put(InetAddress.getByName(srcIp).address)
        bb.put(InetAddress.getByName(dstIp).address)
        // IP 头校验
        var sum = 0
        val arr = bb.array()
        for (i in 0 until 20 step 2) {
            sum += ((arr[i].toInt() and 0xFF) shl 8) or (arr[i + 1].toInt() and 0xFF)
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        bb.putShort(10, (sum.inv() and 0xFFFF).toShort())
        bb.putShort((8 + payload.size).toShort())
        bb.putShort(0)
        bb.putShort(srcPort.toShort()); bb.putShort(dstPort.toShort())
        bb.putShort((8 + payload.size).toShort()); bb.putShort(0)
        bb.put(payload)
        return bb.array()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_ID)
            .setContentTitle(CH_NAME)
            .setContentText("active")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() { stopSelf() }

    override fun onDestroy() {
        running = false
        for ((_, s) in socks) { try { s.close() } catch (_: Throwable) {} }
        socks.clear()
        for ((_, t) in backThreads) t.interrupt()
        backThreads.clear()
        try { outS?.close() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
        EspState.reset()
        Log.i(TAG, "vpn stopped")
        super.onDestroy()
    }
}
