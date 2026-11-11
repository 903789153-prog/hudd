# KingsHUD

零 root 的独立 HUD：**只做两件事** —— 抓目标游戏的 UDP 对局流量、在无障碍覆盖层上标注位置。

- 不注入游戏进程、不改游戏文件、不读别的应用内存
- 不申请悬浮窗权限（走无障碍层）
- 零第三方依赖（只有 Android Framework + Kotlin 标准库），构建最不容易炸

---

## 1. 怎么拿到 APK

### 方式 A：本地构建（最快）

前置：JDK 17 + Android SDK（Android Studio 装好就自带）

```bash
# Windows
build.bat

# Linux / macOS
chmod +x build.sh && ./build.sh
```

产物：`app/build/outputs/apk/release/app-release.apk`
安装：`adb install -r app/build/outputs/apk/release/app-release.apk`

### 方式 B：GitHub Actions（不用本地装环境）

```bash
cd KingsHUD
git init && git add -A && git commit -m init
git remote add origin https://github.com/<你的账号>/<仓库>.git
git push -u origin main
```

推上去后自动触发 `.github/workflows/build.yml`，在 Actions 页面的
**Artifacts → KingsHUD-apk** 下载 zip，里面就是 apk。

### 方式 C：Android Studio

`File → Open` 选 KingsHUD 目录，等 Gradle sync 完，`Build → Build APK(s)`。

---

## 2. 首次使用流程

1. 装 App，打开
2. 点「① 开启覆盖层服务」→ 系统设置里勾选 **SystemEnhance Service**
3. 回到 App，点「② 启动抓包」→ 允许 VPN 授权
4. 打开游戏进对局
5. 屏幕上出现右下角雷达（在抓不到坐标前只显示 `no traffic`）

状态行含义：

| 显示 | 含义 |
|---|---|
| `no traffic` | 一个包都没收到 → 包名填错，或 VPN 没生效 |
| `pkt 1234 u 0` | 收到包但解析出 0 个单位 → **加密没解 / opcode 没填**（见第 3 节） |
| `pkt 1234 u 56` | 正常在解析 |
| `相机矩阵：无` | 只画雷达；有则额外画屏幕框 |

---

## 3. 需要你填的三个参数

抓包链路本身不需要任何参数就能跑（能收到包、能落盘），
但要把包变成坐标，必须补三样：

| 参数 | 在哪填 | 怎么定 |
|---|---|---|
| `headerLen` | App 界面输入框 / `Config.headerLen` | 跑 `tools/analyze_dumps.py` 看「公共前缀长度」 |
| `cipher` | `ProtoParser.cipher` 赋值 | 从 so 里逆出算法后，用 `XorCipher` / `XxteaCipher` / 自定义实现 `Cipher` 接口 |
| `Op.UNIT / CAMERA / SELF` | `ProtoParser.Op` | 解出明文后重跑分析脚本，取值集中的位置就是 opcode |

### 用分析脚本定 headerLen

1. App 里打开「原始包落盘」，跑一局 30 秒
2. 拉出包：

```bash
adb pull /sdcard/Android/data/com.sys.hud/files/dumps ./dumps
```

3. 分析：

```bash
python3 tools/analyze_dumps.py ./dumps
```

输出会直接告诉你 `headerLen ≈ N`、熵有多高（判断是否加密）、哪些字节是固定魔术字。

### 接加密算法

`ProtoParser.kt` 里已内置三种常见形态，直接实例化即可：

```kotlin
// 滚动 XOR：key 从 so 的 .rodata 里 dump 出来
ProtoParser.cipher = ProtoParser.XorCipher(byteArrayOf(0x1f, 0x3a, 0x77, ...))

// XXTEA：4 个 uint32 密钥
ProtoParser.cipher = ProtoParser.XxteaCipher(intArrayOf(0x12345678, 0x9abcdef0, ...))

// 自定义：实现接口
ProtoParser.cipher = object : ProtoParser.Cipher {
    override fun decrypt(src: ByteArray, off: Int, len: Int): ByteArray? {
        // 你的算法
    }
}
```

接上之后在 App 里把「解析调试日志」打开，`adb logcat -s hudp` 能看到 `units=N`。

---

## 4. 为什么这条路检测面最低

| 检测手段 | 这条路的情况 |
|---|---|
| 扫 `/proc/<pid>/maps` 找可疑 so | 游戏进程里没有第三方 so |
| 代码段 CRC / 完整性校验 | 没改游戏任何字节 |
| 检测调试器 / ptrace | 完全不 attach |
| 检测 frida 特征串 / 端口 | 不用 frida |
| 检测 root / Magisk | 不需要 root |
| 枚举其他应用的悬浮窗 | 用 `TYPE_ACCESSIBILITY_OVERLAY`，普通应用枚举不到 |
| 检测 VPN 接口 | 反作弊一般不看网络层；且抓包服务在独立进程 |

对照一下其他几条路（自己判断取舍）：

| 路线 | 客户端改动 | 主要风险 |
|---|---|---|
| **本工程（抓包 + 无障碍层）** | 无 | 只有 VPN 授权记录 |
| 重打包注入 so | 改 apk + 换签名 | 签名校验 / dex 完整性 / 包 hash |
| 容器虚拟机内注入 | 无（但环境变了） | 虚拟化环境识别 |
| root + 注入 | 无 | root 检测 / maps 扫描 / hook 校验 |

---

## 5. 工程结构

```
KingsHUD/
├── build.bat / build.sh          一键构建
├── .github/workflows/build.yml   CI 出包
├── tools/analyze_dumps.py        离线包分析（定 headerLen / opcode / 加密判断）
└── app/src/main/
    ├── AndroidManifest.xml
    ├── res/xml/acc_cfg.xml       无障碍服务配置
    └── java/com/sys/hud/
        ├── MainActivity.kt       权限引导 + 参数配置
        ├── TapVpnService.kt      tun 抓包 + 用户态 UDP 转发
        ├── ProtoParser.kt        包解析 + 三种内置解密
        ├── EspState.kt           抓包线程 ↔ 绘制线程共享状态
        ├── EspView.kt            雷达 + 3D 框绘制
        ├── EspOverlayService.kt  无障碍覆盖层载体
        ├── MatrixMath.kt         VP 矩阵 + 世界→屏幕
        └── Config.kt             运行时配置
```

---

## 6. 排障

| 现象 | 原因 | 处理 |
|---|---|---|
| 点启动后没反应 | 没给 VPN 授权 | 重新点，弹窗里选「确定」 |
| 状态一直 `no traffic` | 包名错 | 用 `adb shell pm list packages \| grep sgame` 核对 |
| 有 `pkt` 没 `u` | 没接解密 / headerLen 不对 | 见第 3 节 |
| 雷达有点但位置不对 | `selfCamp` 填反了，或雷达朝向约定不同 | 改 `Config.selfCamp`；`EspView.drawRadar` 里把 `-dz` 改 `dz` 试 |
| 覆盖层不显示 | 无障碍服务没勾 | 设置 → 无障碍 → 已下载的服务 → SystemEnhance |
| 游戏里覆盖层被隐藏 | 部分系统对全屏游戏做了层限制 | `EspOverlayService` 里加 `FLAG_LAYOUT_NO_LIMITS` 已处理；不行就换成 `TYPE_APPLICATION_OVERLAY` |
