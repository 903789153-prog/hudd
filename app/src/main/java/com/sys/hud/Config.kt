package com.sys.hud

/**
 * 运行时配置（MainActivity 改、EspView 读）。
 * 全部走 volatile，避免跨线程可见性问题。
 */
object Config {
    /** 目标应用包名 */
    @Volatile var targetPkg = "com.tencent.tmgp.sgame"

    /** 自己的阵营值（敌方 = camp != selfCamp） */
    @Volatile var selfCamp = 1

    /** 3D 框开关 */
    @Volatile var enableEsp = true

    /** 雷达开关 */
    @Volatile var enableRadar = true

    /** 雷达上是否画血条 */
    @Volatile var showHp = true

    /** 3D 框最远显示距离（世界单位） */
    @Volatile var maxDist = 6000f

    /** 是否把未解密的包落盘 */
    @Volatile var dumpRaw = false

    /** 解析器调试日志 */
    @Volatile var verbose = false

    /** 分片头长度 */
    @Volatile var headerLen = 0
}
