package com.bitperfect.usbbridge.i18n

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
    private const val PREFS_NAME = "bitperfect_i18n_prefs"
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

data class UsbBridgeStrings(
    val isZh: Boolean,
    val langTogglePill: String,
    val subtitle: String,
    // Status badges
    val badgeStreaming: String,
    val badgePrefill: String,
    val badgeSineTest: String,
    val badgeClaimed: String,
    val badgeReady: String,
    val badgeIdle: String,
    // Battery banner
    val batteryTitle: String,
    val batterySub: String,
    val batteryBtn: String,
    // Card 1: USB Hardware
    val usbCardTitle: String,
    val usbFoundCount: (Int) -> String,
    val usbStandbyTag: (String) -> String,
    val usbSpecLine: (String, String, String) -> String,
    val usbHwVolNone: String,
    val usbArmedReadyHint: String,
    val usbPermGranted: String,
    val usbPermNeeded: String,
    val usbWaitingDialog: String,
    val usbConnecting: String,
    val usbConnectErr: (String) -> String,
    val usbPlugInHint: String,
    val btnRescan: String,
    val btnReleaseDac: String,
    val btnInspectHw: String,
    val btnReleaseStandby: String,
    val btnScanUsb: String,
    val btnArmReady: String,
    val btnGrantConnect: String,
    // Card 2: Audio Bridge
    val bridgeCardTitle: String,
    val modeBadgeAuto: String,
    val modeBadgeManual: String,
    val modeManualTab: String,
    val modeShizukuTab: String,
    val rate44Title: String,
    val rate44Sub: String,
    val rate48Title: String,
    val rate48Sub: String,
    val shizukuNotRunning: String,
    val shizukuNeedPerm: String,
    val shizukuBinding: String,
    val shizukuReadyBadge: (Int) -> String,
    val shizukuErrBadge: String,
    val shizukuActiveHint: (Int?) -> String,
    val shizukuIdleHint: String,
    val shizukuFallbackHint: String,
    val btnRefresh: String,
    val btnAuthorize: String,
    val sysVolZeroTitle: (Int) -> String,
    val sysVolLowTitle: (Int, Int) -> String,
    val sysVolSub: String,
    val btnMaxVol: String,
    val rootConflictAlert: String,
    // 3 Metric Tiles
    val tileSourceLabel: String,
    val tileSourceIdle: String,
    val tileSourceShizuku: String,
    val tileSourceManual: String,
    val tileDacLabel: String,
    val tileDacIntDiv: (Int, Int) -> String,
    val tileDacLossless: (Int) -> String,
    val tileDacIdle: (Int) -> String,
    val tileBufLabel: String,
    val tileBufZeroUnderrun: String,
    val tileBufPrefilling: String,
    val tileBufUnderrun: (Long) -> String,
    val tileBufIdle: String,
    // Status & Buttons
    val statusIdle: String,
    val statusStreamingAuto: String,
    val statusStreamingManual: String,
    val pathSummaryIntDiv: (String, String, Int, String) -> String,
    val pathSummaryZeroSrc: (String, String, String) -> String,
    val btnStartBridge: String,
    val btnStopBridge: String,
    val btnOpenAppleMusic: String,
    val btnQuickResync: String,
    // Card 3: Gain & Pipeline
    val gainCardTitle: String,
    val preset0dB: String,
    val bitDepthLabel: String,
    val bitDepth32Hint: String,
    val bitDepth24Hint: String,
    val seg24Bit: String,
    val seg32Bit: String,
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
    val btnCopyReport: String,
    val standbyInspectDesc: String,
    val btnReadDescriptors: String,
    val connectToViewTopology: String,
    // Toasts
    val toastNeedRecordPerm: String,
    val toastMpCancelled: String,
    val toastAppleMusicMissing: String,
    val toastSnapshotCopied: String,
    val toastLogsCopied: String,
    val toastReportCopied: String
)

val ZhUsbBridgeStrings = UsbBridgeStrings(
    isZh = true,
    langTogglePill = "EN",
    subtitle = "无 Root / Shizuku USB 独占无损音频桥",
    badgeStreaming = "直通中",
    badgePrefill = "预缓冲",
    badgeSineTest = "测试音",
    badgeClaimed = "已接管",
    badgeReady = "已就绪",
    badgeIdle = "未连接",
    batteryTitle = "建议开启后台无限制省电豁免",
    batterySub = "防止切到 Apple Music 后台听歌时被系统冻结音频线程",
    batteryBtn = "去开启",
    usbCardTitle = "USB 解码耳放硬件",
    usbFoundCount = { n -> "发现 $n 台设备" },
    usbStandbyTag = { vidPid -> "待命 · $vidPid" },
    usbSpecLine = { speed, maxKhz, fu -> "$speed · 最高 $maxKhz kHz · 硬件音量 $fu" },
    usbHwVolNone = "不支持",
    usbArmedReadyHint = "USB 权限已就绪 · 点击「启动直通」自动完成两阶段无损接管",
    usbPermGranted = "已授予 USB 权限",
    usbPermNeeded = "待授予 USB 权限",
    usbWaitingDialog = "正在等待系统 USB 权限确认...",
    usbConnecting = "正在初始化 libusb 音频引擎...",
    usbConnectErr = { msg -> "连接异常: $msg" },
    usbPlugInHint = "请插入 Type-C USB 解码耳放小尾巴",
    btnRescan = "刷新总线",
    btnReleaseDac = "释放 DAC",
    btnInspectHw = "读取硬件参数",
    btnReleaseStandby = "取消待命",
    btnScanUsb = "扫描 USB",
    btnArmReady = "就绪待命",
    btnGrantConnect = "授权并连接",
    bridgeCardTitle = "Apple Music 无损音频桥",
    modeBadgeAuto = "ADB 自动切频",
    modeBadgeManual = "手动基准锁定",
    modeManualTab = "无 Root 手动",
    modeShizukuTab = "Shizuku 自动",
    rate44Title = "44.1 kHz 基准",
    rate44Sub = "适配 44.1 / 88.2 / 176.4k",
    rate48Title = "48.0 kHz 基准",
    rate48Sub = "适配 48 / 96 / 192k",
    shizukuNotRunning = "Shizuku 未运行",
    shizukuNeedPerm = "Shizuku 待授权",
    shizukuBinding = "Shizuku 连接中...",
    shizukuReadyBadge = { uid -> if (uid == 2000) "Shizuku ADB 已就绪 (uid=2000)" else "Shizuku 已就绪 (uid=$uid)" },
    shizukuErrBadge = "Shizuku 连接异常",
    shizukuActiveHint = { hz ->
        if (hz != null && hz > 0) "实时嗅探锁定: ${"%.1f".format(hz / 1000.0)} kHz"
        else "正在监听 Apple Music 播放状态..."
    },
    shizukuIdleHint = "自动嗅探 Apple Music 采样率并无感匹配 44.1 / 48 kHz",
    shizukuFallbackHint = "未授权时将自动回退使用手动采样率基准",
    btnRefresh = "刷新",
    btnAuthorize = "授权",
    sysVolZeroTitle = { max -> "系统媒体音量为 0/$max（录屏内录将无声！）" },
    sysVolLowTitle = { cur, max -> "系统媒体音量 $cur/$max（未满格会损失数字位深）" },
    sysVolSub = "建议保持系统音量满格 0dB，用下方 DAC 硬件音量调节响度",
    btnMaxVol = "一键满格",
    rootConflictAlert = "检测到 -84.3 dBFS 特征静音流：同机安装的 Root 版正在拦截 Apple Music，请先停止 Root 版直通或在 LSPosed 中临时关闭模块。",
    tileSourceLabel = "音源规格",
    tileSourceIdle = "待启动",
    tileSourceShizuku = "Shizuku 嗅探",
    tileSourceManual = "手动基准",
    tileDacLabel = "DAC 硬件",
    tileDacIntDiv = { div, bits -> "${div}:1 整数 · ${bits}b" },
    tileDacLossless = { bits -> "1:1 无损 · ${bits}b" },
    tileDacIdle = { bits -> "S$bits 独占" },
    tileBufLabel = "SPSC 缓冲",
    tileBufZeroUnderrun = "零欠载稳流",
    tileBufPrefilling = "预蓄水中",
    tileBufUnderrun = { n -> "欠载 $n" },
    tileBufIdle = "环形缓冲",
    statusIdle = "待启动 · 接入 DAC 后点击启动直通",
    statusStreamingAuto = "Shizuku 自动直通运行中",
    statusStreamingManual = "无 Root 独占直通运行中",
    pathSummaryIntDiv = { src, eff, div, fmt -> "音源 $src -> DAC $eff [${div}:1 整数分频] · $fmt" },
    pathSummaryZeroSrc = { src, eff, fmt -> "音源 $src -> DAC $eff [1:1 Zero-SRC] · $fmt" },
    btnStartBridge = "启动直通",
    btnStopBridge = "停止直通",
    btnOpenAppleMusic = "打开 Apple Music",
    btnQuickResync = "无拔插重同步时钟 (保留硬件音量)",
    gainCardTitle = "DAC 硬件音量与管线",
    preset0dB = "0 dB 满幅",
    bitDepthLabel = "USB 传输位深",
    bitDepth32Hint = "Alt#3 · S32_LE",
    bitDepth24Hint = "Alt#2 · S24_3LE (推荐)",
    seg24Bit = "24-bit (S24)",
    seg32Bit = "32-bit (S32)",
    spscLabel = "SPSC 抗抖动缓冲",
    spscTargetHint = { ms -> "目标 $ms ms" },
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
    btnCopyReport = "复制完整 UAC 描述符报告",
    standbyInspectDesc = "当前处于待命未剥离状态，启动直通或点击下方按钮即可读取完整 UAC 硬件拓扑报告。",
    btnReadDescriptors = "读取 DAC 描述符",
    connectToViewTopology = "接入并连接 USB DAC 后显示完整硬件拓扑",
    toastNeedRecordPerm = "需要录音权限才能使用 MediaProjection 捕获内部无损音频",
    toastMpCancelled = "已取消 MediaProjection 音频捕获授权",
    toastAppleMusicMissing = "未找到 Apple Music 应用",
    toastSnapshotCopied = "已抓取诊断快照并复制到剪贴板",
    toastLogsCopied = "已复制全部实时日志到剪贴板",
    toastReportCopied = "已复制完整 USB 描述符报告"
)

val EnUsbBridgeStrings = UsbBridgeStrings(
    isZh = false,
    langTogglePill = "中文",
    subtitle = "Zero-Root / Shizuku Exclusive USB DAC Bridge",
    badgeStreaming = "Streaming",
    badgePrefill = "Buffering",
    badgeSineTest = "Sine Test",
    badgeClaimed = "Claimed",
    badgeReady = "Armed",
    badgeIdle = "No DAC",
    batteryTitle = "Unrestricted Background Battery Recommended",
    batterySub = "Prevents OS from throttling audio threads while Apple Music is in foreground",
    batteryBtn = "Allow",
    usbCardTitle = "USB DAC Hardware",
    usbFoundCount = { n -> "$n device(s) found" },
    usbStandbyTag = { vidPid -> "Armed · $vidPid" },
    usbSpecLine = { speed, maxKhz, fu -> "$speed · Up to $maxKhz kHz · HW Vol $fu" },
    usbHwVolNone = "N/A",
    usbArmedReadyHint = "USB permission ready · Tap Start Bridge for glitch-free two-stage takeover",
    usbPermGranted = "USB permission granted",
    usbPermNeeded = "USB permission required",
    usbWaitingDialog = "Waiting for Android USB permission dialog...",
    usbConnecting = "Initializing libusb UAC engine...",
    usbConnectErr = { msg -> "Connection error: $msg" },
    usbPlugInHint = "Connect a USB Type-C Audio DAC dongle",
    btnRescan = "Rescan",
    btnReleaseDac = "Release DAC",
    btnInspectHw = "Inspect DAC",
    btnReleaseStandby = "Disarm",
    btnScanUsb = "Scan USB",
    btnArmReady = "Arm DAC",
    btnGrantConnect = "Connect",
    bridgeCardTitle = "Apple Music Lossless Bridge",
    modeBadgeAuto = "ADB Auto-Rate",
    modeBadgeManual = "Manual Base",
    modeManualTab = "Manual Mode",
    modeShizukuTab = "Shizuku Auto",
    rate44Title = "44.1 kHz Base",
    rate44Sub = "For 44.1 / 88.2 / 176.4k",
    rate48Title = "48.0 kHz Base",
    rate48Sub = "For 48 / 96 / 192k",
    shizukuNotRunning = "Shizuku Not Running",
    shizukuNeedPerm = "Shizuku Permission Needed",
    shizukuBinding = "Connecting Shizuku...",
    shizukuReadyBadge = { uid -> if (uid == 2000) "Shizuku ADB Ready (uid=2000)" else "Shizuku Ready (uid=$uid)" },
    shizukuErrBadge = "Shizuku Error",
    shizukuActiveHint = { hz ->
        if (hz != null && hz > 0) "Live Track Rate: ${"%.1f".format(hz / 1000.0)} kHz"
        else "Monitoring Apple Music playback..."
    },
    shizukuIdleHint = "Auto-detects Apple Music track rate & locks 44.1 / 48 kHz",
    shizukuFallbackHint = "Falls back to Manual Base rate until Shizuku is authorized",
    btnRefresh = "Refresh",
    btnAuthorize = "Grant",
    sysVolZeroTitle = { max -> "System Media Volume is 0/$max (Capture Muted!)" },
    sysVolLowTitle = { cur, max -> "System Volume $cur/$max (Set to 100% for Full Bit-Depth)" },
    sysVolSub = "Keep phone media volume at 100% (0 dB) and adjust loudness via DAC HW Volume below",
    btnMaxVol = "Set 100%",
    rootConflictAlert = "Detected -84.3 dBFS dither signature: BitPerfect Root DAC's LSPosed hook is intercepting Apple Music on this device. Stop Root DAC or disable its LSPosed module first.",
    tileSourceLabel = "Source",
    tileSourceIdle = "Standby",
    tileSourceShizuku = "Shizuku Auto",
    tileSourceManual = "Manual Base",
    tileDacLabel = "DAC Lock",
    tileDacIntDiv = { div, bits -> "${div}:1 Int · ${bits}b" },
    tileDacLossless = { bits -> "1:1 Zero-SRC · ${bits}b" },
    tileDacIdle = { bits -> "S$bits USB" },
    tileBufLabel = "SPSC Buffer",
    tileBufZeroUnderrun = "0 Underruns",
    tileBufPrefilling = "Pre-filling",
    tileBufUnderrun = { n -> "Underrun $n" },
    tileBufIdle = "Ring Buffer",
    statusIdle = "Standby · Connect DAC and tap Start Bridge",
    statusStreamingAuto = "Shizuku Auto Bridge Active",
    statusStreamingManual = "Zero-Root Exclusive Bridge Active",
    pathSummaryIntDiv = { src, eff, div, fmt -> "Src $src -> DAC $eff [${div}:1 Int] · $fmt" },
    pathSummaryZeroSrc = { src, eff, fmt -> "Src $src -> DAC $eff [1:1 Zero-SRC] · $fmt" },
    btnStartBridge = "Start Bridge",
    btnStopBridge = "Stop Bridge",
    btnOpenAppleMusic = "Apple Music",
    btnQuickResync = "Quick Clock Resync (Keep HW Volume)",
    gainCardTitle = "DAC Hardware Volume & Pipeline",
    preset0dB = "0 dB Unity",
    bitDepthLabel = "USB Bit Depth",
    bitDepth32Hint = "Alt#3 · S32_LE",
    bitDepth24Hint = "Alt#2 · S24_3LE (Rec.)",
    seg24Bit = "24-bit (S24)",
    seg32Bit = "32-bit (S32)",
    spscLabel = "SPSC Jitter Buffer",
    spscTargetHint = { ms -> "Target $ms ms" },
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
    btnCopyReport = "Copy Full UAC Descriptor Report",
    standbyInspectDesc = "DAC is armed in standby. Start Bridge or tap below to inspect full UAC 2.0 topology.",
    btnReadDescriptors = "Inspect UAC Report",
    connectToViewTopology = "Connect USB DAC to view hardware descriptor report",
    toastNeedRecordPerm = "Audio permission is required for MediaProjection AudioPlaybackCapture",
    toastMpCancelled = "MediaProjection authorization cancelled",
    toastAppleMusicMissing = "Apple Music app not found",
    toastSnapshotCopied = "Diagnostic snapshot copied to clipboard",
    toastLogsCopied = "Real-time logs copied to clipboard",
    toastReportCopied = "USB descriptor report copied to clipboard"
)
