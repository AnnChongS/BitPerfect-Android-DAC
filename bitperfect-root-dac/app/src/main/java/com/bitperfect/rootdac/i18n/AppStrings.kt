package com.bitperfect.rootdac.i18n

import android.content.Context
import android.content.res.Resources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class LanguagePref(val code: String) {
    AUTO("auto"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromCode(code: String?): LanguagePref = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

object AppLanguageManager {
    private const val PREFS_NAME = "bitperfect_root_i18n_prefs"
    private const val KEY_LANG = "lang_pref"

    private val _pref = MutableStateFlow(LanguagePref.AUTO)
    val pref: StateFlow<LanguagePref> = _pref.asStateFlow()

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _pref.value = LanguagePref.fromCode(sp.getString(KEY_LANG, LanguagePref.AUTO.code))
    }

    fun setLanguagePref(context: Context, next: LanguagePref) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_LANG, next.code).apply()
        _pref.value = next
    }

    fun toggleLanguage(context: Context) {
        val currentlyZh = resolveIsChinese(_pref.value)
        setLanguagePref(context, if (currentlyZh) LanguagePref.EN else LanguagePref.ZH)
    }

    fun isChinese(context: Context? = null): Boolean {
        if (context != null && _pref.value == LanguagePref.AUTO) {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _pref.value = LanguagePref.fromCode(sp.getString(KEY_LANG, LanguagePref.AUTO.code))
        }
        return resolveIsChinese(_pref.value)
    }

    fun resolveIsChinese(pref: LanguagePref): Boolean = when (pref) {
        LanguagePref.ZH -> true
        LanguagePref.EN -> false
        LanguagePref.AUTO -> isSystemChinese()
    }

    private fun isSystemChinese(): Boolean {
        val locale = runCatching {
            val list = Resources.getSystem().configuration.locales
            if (!list.isEmpty) list[0] else Locale.getDefault()
        }.getOrDefault(Locale.getDefault())
        return locale.language.startsWith("zh", ignoreCase = true)
    }
}

data class RootDacStrings(
    val isZh: Boolean,
    val langTogglePill: String,
    val subtitle: String,
    // Status badges
    val badgeNoRoot: String,
    val badgeStreaming: String,
    val badgeWaitingPlay: String,
    val badgeSineTest: String,
    val badgeClaimed: String,
    val badgeIdle: String,
    // Banners
    val noRootBanner: String,
    val batteryTitle: String,
    val batterySub: String,
    val batteryBtn: String,
    // Card 1: USB Hardware
    val usbCardTitle: String,
    val usbFoundCount: (Int) -> String,
    val usbSpecLine: (String, String, String) -> String,
    val usbHwVolNone: String,
    val usbPermGranted: String,
    val usbPermNeeded: String,
    val usbWaitingDialog: String,
    val usbConnecting: String,
    val usbConnectErr: (String) -> String,
    val usbPlugInHint: String,
    val btnRescan: String,
    val btnReleaseDac: String,
    val btnScanUsb: String,
    val btnClaimDac: String,
    // Card 2: Source Bridge
    val bridgeCardTitle: String,
    val modeBadgeLsp: String,
    val modeBadgeLoopback: String,
    val segLspDirect: String,
    val segRootLoopback: String,
    val lspHandshakenTitle: (String) -> String,
    val lspListeningTitle: String,
    val lspIdleTitle: String,
    val rootLoopbackActiveTitle: String,
    val rootLoopbackIdleTitle: String,
    val lspHandshakenSub: String,
    val lspSetupSub: String,
    val rootLoopbackSub: String,
    // 3 Metric Tiles
    val tileSourceLabel: String,
    val tileSourceSine: String,
    val tileSourceWaiting: String,
    val tileDacLabel: String,
    val tileDacAlt0: String,
    val tileDacUnclaimed: String,
    val tileBufLabel: String,
    val tileBufCapped48k: String,
    val tileBufBitPerfect: String,
    val tileBufPrefilling: String,
    val tileBufUnderrun: (Long) -> String,
    val tileBufIdle: String,
    // Status & Buttons
    val statusIdle: String,
    val statusWaitingPlay: String,
    val pathSummaryBitPerfect: (Int, String, String, String) -> String,
    val pathSummaryCapped: (Int, String, String, String) -> String,
    val btnStartBridge: String,
    val btnStopBridge: String,
    val btnOpenAppleMusic: String,
    // Card 3: Gain & Pipeline
    val gainCardTitle: String,
    val preset0dB: String,
    val bitDepthLabel: String,
    val bitDepthAutoHint: String,
    val bitDepth32Hint: String,
    val bitDepth24Hint: String,
    val segBitAuto: String,
    val segBit24: String,
    val segBit32: String,
    val spscLabel: String,
    val spscTargetHint: (Int) -> String,
    // Card 4: Workbench
    val tabLogs: (Int) -> String,
    val tabSineTest: String,
    val tabDevice: String,
    val btnCopyLogs: String,
    val btnSnapshot: String,
    val btnClear: String,
    val emptyLogs: String,
    val sineDesc: String,
    val btnStopSine: String,
    val btnStartSine: String,
    val langSettingLabel: String,
    val langAuto: String,
    val schedFifoActive: (Int) -> String,
    val schedIsoSummary: (String) -> String,
    val btnCopyReport: String,
    val connectToViewTopology: String,
    // Toasts
    val toastAppleMusicMissing: String,
    val toastSnapshotCopied: String,
    val toastLogsCopied: String,
    val toastReportCopied: String
)

val ZhRootDacStrings = RootDacStrings(
    isZh = true,
    langTogglePill = "EN",
    subtitle = "Root + LSPosed 真 24-bit/192kHz 源码直通",
    badgeNoRoot = "无 Root",
    badgeStreaming = "直通中",
    badgeWaitingPlay = "待播放",
    badgeSineTest = "测试音",
    badgeClaimed = "已接管",
    badgeIdle = "未连接",
    noRootBanner = "尚未获取 Root 授权：请在 Magisk / KernelSU / APatch 中允许本应用超级用户权限。",
    batteryTitle = "建议开启后台无限制省电豁免",
    batterySub = "保障锁屏与后台切回 Apple Music 长时高码率稳流",
    batteryBtn = "去开启",
    usbCardTitle = "USB 解码耳放硬件",
    usbFoundCount = { n -> "发现 $n 台设备" },
    usbSpecLine = { speed, maxKhz, fu -> "$speed · 最高 $maxKhz kHz · 硬件音量 $fu" },
    usbHwVolNone = "不支持",
    usbPermGranted = "已授予 USB 权限",
    usbPermNeeded = "待授予 USB 权限",
    usbWaitingDialog = "正在等待系统 USB 权限确认...",
    usbConnecting = "正在初始化 libusb 音频引擎...",
    usbConnectErr = { msg -> "连接异常: $msg" },
    usbPlugInHint = "请插入 Type-C USB 解码耳放小尾巴",
    btnRescan = "刷新总线",
    btnReleaseDac = "释放 DAC",
    btnScanUsb = "扫描 USB",
    btnClaimDac = "独占接管",
    bridgeCardTitle = "Apple Music 原生源码桥",
    modeBadgeLsp = "192kHz 进程直截",
    modeBadgeLoopback = "Root 环回备选",
    segLspDirect = "LSPosed 直截",
    segRootLoopback = "Root 环回 (48k)",
    lspHandshakenTitle = { pids -> "LSPosed 已握手 Apple Music (PID $pids)" },
    lspListeningTitle = "本地直截端口已就绪 · 等待 Apple Music 播放",
    lspIdleTitle = "LSPosed 进程内 AudioTrack 零损耗直截引擎",
    rootLoopbackActiveTitle = "Root AudioPolicy 环回监听运行中",
    rootLoopbackIdleTitle = "Root 环回备选引擎 (免 LSPosed)",
    lspHandshakenSub = "完全绕过 AudioFlinger 与 r_submix · 最高 192kHz 1:1 源码直通",
    lspSetupSub = "需在 LSPosed 中启用本模块并勾选 Apple Music 作用域",
    rootLoopbackSub = "受 Android 系统 r_submix HAL 限制，最高采样率为 48.0 kHz",
    tileSourceLabel = "音源规格",
    tileSourceSine = "440Hz 测试音",
    tileSourceWaiting = "等待播放",
    tileDacLabel = "DAC 硬件",
    tileDacAlt0 = "Alt#0 待机",
    tileDacUnclaimed = "未接管",
    tileBufLabel = "SPSC 缓冲",
    tileBufCapped48k = "48k 稳流",
    tileBufBitPerfect = "1:1 零欠载",
    tileBufPrefilling = "预蓄水中",
    tileBufUnderrun = { n -> "欠载 $n" },
    tileBufIdle = "4MB 零锁缓冲",
    statusIdle = "待机 · 点击启动源码直通将自动接管并监听",
    statusWaitingPlay = "源码直通已就绪 · 打开 Apple Music 播放即可",
    pathSummaryBitPerfect = { bits, srcKhz, fmt, dacKhz -> "音源 ${bits}b/$srcKhz -> DAC $fmt/$dacKhz [1:1 Bit-Perfect]" },
    pathSummaryCapped = { bits, srcKhz, fmt, dacKhz -> "音源 ${bits}b/$srcKhz -> r_submix -> DAC $fmt/$dacKhz [48k 上限]" },
    btnStartBridge = "启动源码直通",
    btnStopBridge = "停止源码直通",
    btnOpenAppleMusic = "打开 Apple Music",
    gainCardTitle = "DAC 硬件音量与管线",
    preset0dB = "0 dB 满幅",
    bitDepthLabel = "USB 传输位深",
    bitDepthAutoHint = "自动匹配音源 (16/24/32b)",
    bitDepth32Hint = "Alt#3 · S32_LE",
    bitDepth24Hint = "Alt#2 · S24_3LE",
    segBitAuto = "自动原生",
    segBit24 = "24-bit",
    segBit32 = "32-bit",
    spscLabel = "SPSC 零锁抗抖动缓冲",
    spscTargetHint = { ms -> "目标 $ms ms (4MB)" },
    tabLogs = { n -> "日志 ($n)" },
    tabSineTest = "晶振自检",
    tabDevice = "设备与设置",
    btnCopyLogs = "复制日志",
    btnSnapshot = "诊断快照",
    btnClear = "清空",
    emptyLogs = "暂无日志",
    sineDesc = "C++ 双精度 440 Hz (-24 dBFS 护耳电平) 纯硬件正弦发生器，绕过 Android 音频栈直推 USB 端点，校验 44.1k ~ 192kHz 硬件锁相环：",
    btnStopSine = "停止 440Hz 测试音",
    btnStartSine = "启动 440Hz 晶振自检",
    langSettingLabel = "界面语言 / Language",
    langAuto = "跟随系统",
    schedFifoActive = { tid -> "SCHED_FIFO 实时内核调度已激活 (tid=$tid)" },
    schedIsoSummary = { maxKhz -> "12 URBs × 32 微帧异步等时队列 · 最高支持 $maxKhz kHz" },
    btnCopyReport = "复制完整 UAC 描述符报告",
    connectToViewTopology = "接入并独占接管 USB DAC 后显示完整硬件拓扑",
    toastAppleMusicMissing = "未检测到 Apple Music 应用",
    toastSnapshotCopied = "已抓取诊断快照并复制到剪贴板",
    toastLogsCopied = "已复制全部实时日志到剪贴板",
    toastReportCopied = "已复制完整 USB 描述符报告"
)

val EnRootDacStrings = RootDacStrings(
    isZh = false,
    langTogglePill = "中文",
    subtitle = "Root + LSPosed True 24-bit/192kHz Bit-Perfect DAC",
    badgeNoRoot = "No Root",
    badgeStreaming = "Streaming",
    badgeWaitingPlay = "Armed",
    badgeSineTest = "Sine Test",
    badgeClaimed = "Claimed",
    badgeIdle = "No DAC",
    noRootBanner = "Root access not granted: please grant Superuser permission in Magisk / KernelSU / APatch.",
    batteryTitle = "Unrestricted Background Battery Recommended",
    batterySub = "Keeps high-rate 192kHz USB streaming uninterrupted when screen is off",
    batteryBtn = "Allow",
    usbCardTitle = "USB DAC Hardware",
    usbFoundCount = { n -> "$n device(s) found" },
    usbSpecLine = { speed, maxKhz, fu -> "$speed · Up to $maxKhz kHz · HW Vol $fu" },
    usbHwVolNone = "N/A",
    usbPermGranted = "USB permission granted",
    usbPermNeeded = "USB permission required",
    usbWaitingDialog = "Waiting for Android USB permission dialog...",
    usbConnecting = "Initializing libusb UAC engine...",
    usbConnectErr = { msg -> "Connection error: $msg" },
    usbPlugInHint = "Connect a USB Type-C Audio DAC dongle",
    btnRescan = "Rescan",
    btnReleaseDac = "Release DAC",
    btnScanUsb = "Scan USB",
    btnClaimDac = "Claim DAC",
    bridgeCardTitle = "Apple Music Native Source Bridge",
    modeBadgeLsp = "192kHz Direct",
    modeBadgeLoopback = "Root Loopback",
    segLspDirect = "LSPosed Direct",
    segRootLoopback = "Loopback (48k)",
    lspHandshakenTitle = { pids -> "LSPosed Connected to Apple Music (PID $pids)" },
    lspListeningTitle = "Direct Port Listening · Play a Track in Apple Music",
    lspIdleTitle = "LSPosed In-Process AudioTrack Bit-Perfect Engine",
    rootLoopbackActiveTitle = "Root AudioPolicy Loopback Active",
    rootLoopbackIdleTitle = "Root Loopback Fallback (No LSPosed)",
    lspHandshakenSub = "Bypasses AudioFlinger & r_submix · 16/24/32-bit up to 192kHz 1:1 Bit-Perfect",
    lspSetupSub = "Enable this module in LSPosed Manager with Apple Music checked",
    rootLoopbackSub = "Capped at 48.0 kHz by stock Android r_submix HAL",
    tileSourceLabel = "Source",
    tileSourceSine = "440Hz Tone",
    tileSourceWaiting = "Waiting",
    tileDacLabel = "DAC Lock",
    tileDacAlt0 = "Alt#0 Idle",
    tileDacUnclaimed = "Unclaimed",
    tileBufLabel = "SPSC Buffer",
    tileBufCapped48k = "48k Stable",
    tileBufBitPerfect = "1:1 0-Drop",
    tileBufPrefilling = "Pre-filling",
    tileBufUnderrun = { n -> "Underrun $n" },
    tileBufIdle = "4MB Lock-Free",
    statusIdle = "Standby · Tap Start Bridge to auto-claim DAC & listen",
    statusWaitingPlay = "Bridge Ready · Start playback in Apple Music",
    pathSummaryBitPerfect = { bits, srcKhz, fmt, dacKhz -> "Src ${bits}b/$srcKhz -> DAC $fmt/$dacKhz [1:1 Bit-Perfect]" },
    pathSummaryCapped = { bits, srcKhz, fmt, dacKhz -> "Src ${bits}b/$srcKhz -> r_submix -> DAC $fmt/$dacKhz [48k Cap]" },
    btnStartBridge = "Start Bridge",
    btnStopBridge = "Stop Bridge",
    btnOpenAppleMusic = "Apple Music",
    gainCardTitle = "DAC Hardware Volume & Pipeline",
    preset0dB = "0 dB Unity",
    bitDepthLabel = "USB Bit Depth",
    bitDepthAutoHint = "Auto-Match Source (16/24/32b)",
    bitDepth32Hint = "Alt#3 · S32_LE",
    bitDepth24Hint = "Alt#2 · S24_3LE",
    segBitAuto = "Auto Native",
    segBit24 = "24-bit",
    segBit32 = "32-bit",
    spscLabel = "SPSC Lock-Free Buffer",
    spscTargetHint = { ms -> "Target $ms ms (4MB)" },
    tabLogs = { n -> "Logs ($n)" },
    tabSineTest = "440Hz Test",
    tabDevice = "Device & Lang",
    btnCopyLogs = "Copy Logs",
    btnSnapshot = "Snapshot",
    btnClear = "Clear",
    emptyLogs = "No logs yet",
    sineDesc = "Double-precision 440 Hz (-24 dBFS ear-safe) C++ oscillator pushing directly to USB ISO endpoint to verify 44.1k ~ 192kHz PLL lock:",
    btnStopSine = "Stop 440Hz Tone",
    btnStartSine = "Start 440Hz Self-Test",
    langSettingLabel = "Language / 界面语言",
    langAuto = "System",
    schedFifoActive = { tid -> "SCHED_FIFO Real-Time Thread Active (tid=$tid)" },
    schedIsoSummary = { maxKhz -> "12 URBs × 32 uframes Async ISO Queue · Up to $maxKhz kHz" },
    btnCopyReport = "Copy Full UAC Descriptor Report",
    connectToViewTopology = "Claim USB DAC to inspect full hardware descriptor report",
    toastAppleMusicMissing = "Apple Music app not found",
    toastSnapshotCopied = "Diagnostic snapshot copied to clipboard",
    toastLogsCopied = "Real-time logs copied to clipboard",
    toastReportCopied = "USB descriptor report copied to clipboard"
)
