package com.bitperfect.usbbridge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitperfect.usbbridge.BuildConfig
import com.bitperfect.usbbridge.capture.ActiveCaptureConfig
import com.bitperfect.usbbridge.capture.AudioCapturePipeline
import com.bitperfect.usbbridge.capture.DeepBufferKeepAliveGuard
import com.bitperfect.usbbridge.capture.InstalledAppScanner
import com.bitperfect.usbbridge.capture.InstalledAudioApp
import com.bitperfect.usbbridge.capture.ResolvedUidApp
import com.bitperfect.usbbridge.i18n.AppLanguageManager
import com.bitperfect.usbbridge.nativebridge.CaptureHardwareResult
import com.bitperfect.usbbridge.nativebridge.DacProfile
import com.bitperfect.usbbridge.nativebridge.InspectResult
import com.bitperfect.usbbridge.nativebridge.NativeLogListener
import com.bitperfect.usbbridge.nativebridge.NativeUsbAudioBridge
import com.bitperfect.usbbridge.nativebridge.StreamTelemetry
import com.bitperfect.usbbridge.platform.SocCompatibilityProfile
import com.bitperfect.usbbridge.service.MediaProjectionBridgeHolder
import com.bitperfect.usbbridge.service.UsbBridgeService
import com.bitperfect.usbbridge.shizuku.DetectedAudioSpec
import com.bitperfect.usbbridge.shizuku.RemoteSubmixProfile
import com.bitperfect.usbbridge.shizuku.ShizukuConnectionState
import com.bitperfect.usbbridge.shizuku.ShizukuController
import com.bitperfect.usbbridge.shizuku.ShizukuSpecSniffer
import com.bitperfect.usbbridge.shizuku.SnifferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class LogEntry(
    val timestamp: String,
    val level: Int, // 0=D, 1=I, 2=W, 3=E
    val source: String,
    val message: String
)

data class DiscoveredUsbAudioDevice(
    val device: UsbDevice,
    val displayName: String,
    val vidPid: String,
    val hasPermission: Boolean,
    val audioInterfaceCount: Int
)

data class SineTestPreset(
    val sampleRate: Int,
    val bitDepth: Int,
    val label: String
)

data class SystemVolumeState(
    val current: Int = 15,
    val max: Int = 15
) {
    val isFull: Boolean
        get() = max <= 0 || current >= max

    val isMuted: Boolean
        get() = max > 0 && current <= 0
}

enum class BridgeOperatingMode(val shortLabel: String, val desc: String) {
    NON_ROOT_MANUAL(
        shortLabel = "无 Root 手动",
        desc = "零 Root / 零 Shizuku · 手动选择 44.1 / 48 kHz 整数基准"
    ),
    SHIZUKU_AUTO(
        shortLabel = "Shizuku 自动切频",
        desc = "ADB 授权 (uid=2000) · 自动嗅探任意活跃播放器采样率并无感切频"
    )
}

enum class CaptureScopeMode(val code: String) {
    GLOBAL_MEDIA("global"),
    SINGLE_APP("single");

    companion object {
        fun fromCode(code: String?): CaptureScopeMode =
            entries.firstOrNull { it.code == code } ?: GLOBAL_MEDIA
    }
}

/**
 * Dynamic r_submix rate plan supporting:
 *  1. Native Hi-Res 1:1 direct pass-through when the device's r_submix HAL natively advertises
 *     the source rate (e.g. 96 / 192 kHz on devices/ROMs with Hi-Res r_submix) AND DAC supports it;
 *  2. Safe integer-divisor fallback on standard 44.1 / 48 kHz r_submix HALs to eliminate
 *     non-integer SRC & muffled upsampling:
 *       - 44.1k family (44100 / 88200 / 176400 Hz) -> 44100 Hz (1:1 / 2:1 / 4:1 integer)
 *       - 48.0k family (48000 / 96000 / 192000 Hz) -> 48000 Hz (1:1 / 2:1 / 4:1 integer)
 */
data class EffectiveRatePlan(
    val sourceSampleRate: Int,
    val effectiveSampleRate: Int,
    val integerDivisor: Int,
    val isIntegerDownsampled: Boolean,
    val isHiResSubmixNative: Boolean = false
) {
    fun formatPathSummary(dacFormatTag: String): String {
        val srcKhz = "%.1f kHz".format(sourceSampleRate / 1000.0)
        val effKhz = "%.1f kHz".format(effectiveSampleRate / 1000.0)
        return when {
            isHiResSubmixNative -> "$srcKhz -> $effKhz [1:1 Hi-Res Native] · $dacFormatTag"
            isIntegerDownsampled -> "$srcKhz -> $effKhz [${integerDivisor}:1 Int] · $dacFormatTag"
            else -> "$srcKhz -> $effKhz [1:1 Zero-SRC] · $dacFormatTag"
        }
    }
}

data class AudioBridgeState(
    val active: Boolean = false,
    val statusMessage: String = "未启动",
    val targetUid: Int? = null, // null = Global Media/Game Mode
    val captureScopeMode: CaptureScopeMode = CaptureScopeMode.GLOBAL_MEDIA,
    val operatingMode: BridgeOperatingMode = BridgeOperatingMode.NON_ROOT_MANUAL,
    val sourceSpec: DetectedAudioSpec? = null,
    val ratePlan: EffectiveRatePlan? = null,
    val dacHardware: CaptureHardwareResult? = null,
    val captureConfig: ActiveCaptureConfig? = null,
    val reconfigureCount: Int = 0
) {
    fun formatPathSummary(): String? {
        val plan = ratePlan ?: return null
        val dacTag = dacHardware?.shortFormatTag ?: "S24_3LE"
        val appPrefix = sourceSpec?.appLabel?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: ""
        return appPrefix + plan.formatPathSummary(dacTag)
    }
}

sealed class DacConnectionState {
    data object Disconnected : DacConnectionState()
    data class RequestingPermission(val deviceName: String) : DacConnectionState()

    /**
     * Android USB permission is granted and the DAC is armed, while snd-usb-audio remains untouched
     * until MediaProjection & AudioRecord are initialized (glitch-free two-stage sequence).
     */
    data class ArmedStandby(
        val device: UsbDevice,
        val displayName: String,
        val vidPid: String
    ) : DacConnectionState()

    data class Connecting(val deviceName: String) : DacConnectionState()

    data class Connected(
        val device: UsbDevice,
        val profile: DacProfile,
        val fullReport: String
    ) : DacConnectionState()

    data class Error(val message: String) : DacConnectionState()

    val isReadyToStartBridge: Boolean
        get() = this is ArmedStandby || this is Connected
}

private data class ActiveUsbSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val profile: DacProfile
)

@OptIn(FlowPreview::class)
class UsbDacManager(context: Context) {

    companion object {
        private const val TAG = "UsbDacManager"
        private const val PREFS_NAME = "bitperfect_universal_prefs"
        private const val KEY_CAPTURE_SCOPE = "capture_scope_mode"
        private const val KEY_SELECTED_APP_PKG = "selected_app_pkg"

        private const val ACTION_USB_PERMISSION = "com.bitperfect.usbbridge.ACTION_USB_PERMISSION"
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val USB_AUDIO_SUBCLASS_CONTROL = 1
        private const val USB_AUDIO_SUBCLASS_STREAMING = 2
        private const val MAX_LOG_LINES = 650
        private const val VOLUME_DEBOUNCE_MS = 35L
        private const val LOW_LATENCY_THRESHOLD_MS = 85

        val MANUAL_BASE_RATE_OPTIONS = listOf(44100, 48000)

        fun computeEffectiveRatePlan(
            sourceRate: Int,
            profile: DacProfile? = null,
            submixProfile: RemoteSubmixProfile? = null,
            allowHiResSubmixNative: Boolean = true
        ): EffectiveRatePlan {
            // 1. Dynamic r_submix native Hi-Res pass-through when HAL & DAC both support sourceRate
            val submixSupportsNative = allowHiResSubmixNative &&
                sourceRate > 48000 &&
                submixProfile != null &&
                sourceRate in submixProfile.supportedRates
            val dacSupportsSource = profile == null ||
                profile.allSupportedSampleRates.contains(sourceRate)

            if (submixSupportsNative && dacSupportsSource) {
                return EffectiveRatePlan(
                    sourceSampleRate = sourceRate,
                    effectiveSampleRate = sourceRate,
                    integerDivisor = 1,
                    isIntegerDownsampled = false,
                    isHiResSubmixNative = true
                )
            }

            // 2. Universal safe integer-divisor mapping for standard 44.1 / 48 kHz r_submix HALs
            val baseRate = when {
                sourceRate <= 44100 -> 44100
                sourceRate % 44100 == 0 -> 44100
                sourceRate % 48000 == 0 -> 48000
                else -> 48000
            }
            val finalRate = profile?.resolveDacSampleRate(baseRate)?.first ?: baseRate
            val divisor = if (finalRate > 0 && sourceRate > finalRate && sourceRate % finalRate == 0) {
                sourceRate / finalRate
            } else {
                1
            }
            return EffectiveRatePlan(
                sourceSampleRate = sourceRate,
                effectiveSampleRate = finalRate,
                integerDivisor = divisor,
                isIntegerDownsampled = sourceRate > finalRate,
                isHiResSubmixNative = false
            )
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val socProfile: SocCompatibilityProfile = SocCompatibilityProfile.detect()

    private val reconfigureMutex = Mutex()
    private val keepAliveAppliedPackages = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var activeSession: ActiveUsbSession? = null

    @Volatile
    private var armedDevice: UsbDevice? = null

    @Volatile
    private var activeMediaProjection: MediaProjection? = null

    @Volatile
    private var lastAppliedVolumeDb: Double = Double.NaN

    @Volatile
    private var hiResSubmixCaptureFailed: Boolean = false

    // Auto-Heal Watchdog state (detects zeroDropout bursts in both Non-Root & Shizuku modes)
    @Volatile
    private var zeroWatchWindowStartMs: Long = 0L

    @Volatile
    private var zeroWatchWindowBaseEvents: Long = 0L

    @Volatile
    private var lastAutoHealMs: Long = 0L

    @Volatile
    private var streamActiveSinceMs: Long = 0L

    private var receiversRegistered = false
    private var telemetryJob: Job? = null
    private val volumeInputFlow = MutableSharedFlow<Double>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val shizukuController = ShizukuController(appContext) { level, msg ->
        appendLog(level, "SHIZUKU", msg)
    }

    private val specSniffer = ShizukuSpecSniffer(appContext, shizukuController) { level, msg ->
        appendLog(level, "SNIFF", msg)
    }

    private val capturePipeline = AudioCapturePipeline { level, msg ->
        appendLog(level, "CAP", msg)
    }

    // Strictly gated behind socProfile.useQualcommPalPreWarm (never started on XRING/Exynos/Dimensity/Tensor!)
    private val deepBufferGuard = DeepBufferKeepAliveGuard { level, msg ->
        appendLog(level, "PAL", msg)
    }

    val shizukuState: StateFlow<ShizukuConnectionState> = shizukuController.state
    val snifferStatus: StateFlow<SnifferStatus> = specSniffer.status

    private val _remoteSubmixProfile = MutableStateFlow<RemoteSubmixProfile?>(null)
    val remoteSubmixProfile: StateFlow<RemoteSubmixProfile?> = _remoteSubmixProfile.asStateFlow()

    private val _captureScopeMode = MutableStateFlow(
        CaptureScopeMode.fromCode(prefs.getString(KEY_CAPTURE_SCOPE, CaptureScopeMode.GLOBAL_MEDIA.code))
    )
    val captureScopeMode: StateFlow<CaptureScopeMode> = _captureScopeMode.asStateFlow()

    private val _selectedApp = MutableStateFlow<InstalledAudioApp?>(null)
    val selectedApp: StateFlow<InstalledAudioApp?> = _selectedApp.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAudioApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledAudioApp>> = _installedApps.asStateFlow()

    private val _isScanningApps = MutableStateFlow(false)
    val isScanningApps: StateFlow<Boolean> = _isScanningApps.asStateFlow()

    private val _trackedPlayer = MutableStateFlow<ResolvedUidApp?>(null)
    val trackedPlayer: StateFlow<ResolvedUidApp?> = _trackedPlayer.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredUsbAudioDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredUsbAudioDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<DacConnectionState>(DacConnectionState.Disconnected)
    val connectionState: StateFlow<DacConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private val _bridgeState = MutableStateFlow(AudioBridgeState())
    val bridgeState: StateFlow<AudioBridgeState> = _bridgeState.asStateFlow()

    private val _operatingMode = MutableStateFlow(BridgeOperatingMode.NON_ROOT_MANUAL)
    val operatingMode: StateFlow<BridgeOperatingMode> = _operatingMode.asStateFlow()

    private val _manualSampleRate = MutableStateFlow(44100)
    val manualSampleRate: StateFlow<Int> = _manualSampleRate.asStateFlow()

    private val _selectedPreset = MutableStateFlow(SineTestPreset(44100, 24, "44.1k / 24b"))
    val selectedPreset: StateFlow<SineTestPreset> = _selectedPreset.asStateFlow()

    private val _volumeDb = MutableStateFlow(0.0)
    val volumeDb: StateFlow<Double> = _volumeDb.asStateFlow()

    private val _bufferDurationMs = MutableStateFlow(250)
    val bufferDurationMs: StateFlow<Int> = _bufferDurationMs.asStateFlow()

    private val _preferredBridgeBitDepth = MutableStateFlow(24) // 24 = S24_3LE (Alt#2), 32 = S32_LE (Alt#3)
    val preferredBridgeBitDepth: StateFlow<Int> = _preferredBridgeBitDepth.asStateFlow()

    private val _systemMusicVolume = MutableStateFlow(readSystemMusicVolume())
    val systemMusicVolume: StateFlow<SystemVolumeState> = _systemMusicVolume.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val nativeLogListener = object : NativeLogListener {
        override fun onNativeLog(level: Int, message: String) {
            appendLog(level = level, source = "C++", message = message)
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            refreshDevices()
            if (device == null) {
                appendLog(3, "KT", "USB 授权回调缺少设备信息")
                _connectionState.value = DacConnectionState.Error("USB 授权回调异常")
                return
            }
            if (granted) {
                appendLog(1, "KT", "USB 权限已授予: ${deviceDisplayName(device)}")
                onUsbPermissionReady(device)
            } else {
                appendLog(2, "KT", "用户拒绝了 USB 权限: ${deviceDisplayName(device)}")
                _connectionState.value = DacConnectionState.Error("未授予 USB 独占访问权限")
            }
        }
    }

    private val hotplugAndVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    appendLog(1, "KT", "检测到 USB 接入: ${dev?.let(::deviceDisplayName) ?: "unknown"}")
                    refreshDevices()
                    refreshSystemMusicVolume()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    appendLog(2, "KT", "检测到 USB 拔出: ${dev?.let(::deviceDisplayName) ?: "unknown"}")
                    if (dev != null &&
                        (activeSession?.device?.deviceId == dev.deviceId || armedDevice?.deviceId == dev.deviceId)
                    ) {
                        disconnect("USB DAC 已物理拔出")
                    }
                    refreshDevices()
                    refreshSystemMusicVolume()
                }
                ACTION_VOLUME_CHANGED -> {
                    refreshSystemMusicVolume()
                }
            }
        }
    }

    init {
        scope.launch {
            volumeInputFlow
                .debounce(VOLUME_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { db ->
                    applyHardwareVolumeDb(db)
                }
        }

        // Auto-apply background keep-alive & switch to Shizuku Auto mode once Shizuku UserService becomes ready
        scope.launch {
            shizukuController.state.collect { st ->
                if (st is ShizukuConnectionState.Ready) {
                    if (_operatingMode.value == BridgeOperatingMode.NON_ROOT_MANUAL && !_bridgeState.value.active) {
                        _operatingMode.value = BridgeOperatingMode.SHIZUKU_AUTO
                        appendLog(1, "SHIZUKU", "Shizuku ADB 已就绪 (uid=${st.uid})，已自动启用「Shizuku 自动切频」模式")
                    }
                    applyKeepAliveForCurrentTargets()
                }
            }
        }

        MediaProjectionBridgeHolder.onProjectionReady = { mp ->
            onMediaProjectionReady(mp)
        }
        MediaProjectionBridgeHolder.onProjectionStopped = {
            appendLog(2, "KT", "系统已结束 MediaProjection 音频捕获会话")
            stopAudioBridge()
        }
        MediaProjectionBridgeHolder.onProjectionError = { err ->
            appendLog(3, "KT", err)
            _bridgeState.update { it.copy(active = false, statusMessage = err) }
        }
    }

    fun start() {
        NativeUsbAudioBridge.setLogListener(nativeLogListener)
        NativeUsbAudioBridge.setIsoThreadListener(null) // Pure Zero-Root architecture
        shizukuController.start()
        refreshSystemMusicVolume()
        restoreSavedSelectedApp()
        refreshInstalledApps()

        appendLog(
            1,
            "KT",
            "BitPerfect USB Bridge v${BuildConfig.VERSION_NAME} (全平台通用 · 多播放器 + 音游低延迟) 已启动"
        )
        appendLog(1, "SOC", "${socProfile.diagnosticSummary()} -> ${socProfile.strategyDescription(isZh = true)}")

        if (!receiversRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                permissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val sysFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_VOLUME_CHANGED)
            }
            ContextCompat.registerReceiver(
                appContext,
                hotplugAndVolumeReceiver,
                sysFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiversRegistered = true
        }
        refreshDevices()
    }

    fun stop() {
        MediaProjectionBridgeHolder.onProjectionReady = null
        MediaProjectionBridgeHolder.onProjectionStopped = null
        MediaProjectionBridgeHolder.onProjectionError = null
        runBlocking {
            withTimeoutOrNull(1500L) {
                disconnectInternal("Manager stopped")
            }
        }
        shizukuController.stop()
        if (receiversRegistered) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            runCatching { appContext.unregisterReceiver(hotplugAndVolumeReceiver) }
            receiversRegistered = false
        }
        NativeUsbAudioBridge.setLogListener(null)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun formatAllLogs(): String {
        return _logs.value.joinToString(separator = "\n") { entry ->
            val lvl = when (entry.level) {
                0 -> "D"
                1 -> "I"
                2 -> "W"
                else -> "E"
            }
            "${entry.timestamp} [$lvl/${entry.source}] ${entry.message}"
        }
    }

    // -------------------------------------------------------------------------
    // Universal Multi-Player Scope & App Picker Management
    // -------------------------------------------------------------------------

    private fun restoreSavedSelectedApp() {
        val savedPkg = prefs.getString(KEY_SELECTED_APP_PKG, null)?.trim().orEmpty()
        if (savedPkg.isNotEmpty()) {
            val resolved = InstalledAppScanner.resolvePackageToApp(appContext, savedPkg)
            if (resolved != null) {
                _selectedApp.value = resolved
            } else if (_captureScopeMode.value == CaptureScopeMode.SINGLE_APP) {
                // Saved app was uninstalled -> safely revert to Global mode
                _captureScopeMode.value = CaptureScopeMode.GLOBAL_MEDIA
                prefs.edit().putString(KEY_CAPTURE_SCOPE, CaptureScopeMode.GLOBAL_MEDIA.code).apply()
            }
        }
    }

    fun refreshInstalledApps() {
        if (_isScanningApps.value) return
        scope.launch {
            _isScanningApps.value = true
            try {
                val scanned = InstalledAppScanner.scanLaunchableApps(appContext)
                _installedApps.value = scanned
                val curSelPkg = _selectedApp.value?.packageName
                    ?: prefs.getString(KEY_SELECTED_APP_PKG, null)
                if (!curSelPkg.isNullOrBlank()) {
                    scanned.firstOrNull { it.packageName == curSelPkg }?.let { fresh ->
                        _selectedApp.value = fresh
                    }
                }
            } finally {
                _isScanningApps.value = false
            }
        }
    }

    fun setCaptureScopeMode(mode: CaptureScopeMode) {
        if (_captureScopeMode.value == mode) return
        _captureScopeMode.value = mode
        prefs.edit().putString(KEY_CAPTURE_SCOPE, mode.code).apply()

        val label = if (mode == CaptureScopeMode.GLOBAL_MEDIA) {
            "全局媒体/音游自动追踪 (任意 App)"
        } else {
            val appName = _selectedApp.value?.label ?: "待选择应用"
            "指定单个应用锁定 ($appName)"
        }
        appendLog(1, "KT", "捕获范围已切换: $label")

        if (mode == CaptureScopeMode.SINGLE_APP && _selectedApp.value == null) {
            // Wait for user to pick an app from picker before reconfiguring active bridge
            return
        }

        if (_bridgeState.value.active) {
            rebindCaptureScopeIfActive()
        }
    }

    fun selectTargetApp(app: InstalledAudioApp) {
        _selectedApp.value = app
        _captureScopeMode.value = CaptureScopeMode.SINGLE_APP
        prefs.edit()
            .putString(KEY_CAPTURE_SCOPE, CaptureScopeMode.SINGLE_APP.code)
            .putString(KEY_SELECTED_APP_PKG, app.packageName)
            .apply()

        _trackedPlayer.value = ResolvedUidApp(
            uid = app.uid,
            packageName = app.packageName,
            label = app.label,
            icon = app.icon
        )
        appendLog(1, "KT", "已锁定单应用捕获目标: ${app.label} (${app.packageName}, UID=${app.uid})")

        if (shizukuController.state.value.isReady) {
            scope.launch { applyKeepAliveForPackages(listOf(app.packageName)) }
        }

        if (_bridgeState.value.active) {
            rebindCaptureScopeIfActive()
        }
    }

    private fun rebindCaptureScopeIfActive() {
        scope.launch {
            reconfigureMutex.withLock {
                if (!_bridgeState.value.active) return@withLock
                val targetUid = resolveEffectiveTargetUid()
                val curRate = _bridgeState.value.sourceSpec?.sampleRate ?: _manualSampleRate.value

                if (_operatingMode.value == BridgeOperatingMode.SHIZUKU_AUTO && shizukuController.state.value.isReady) {
                    specSniffer.start(
                        targetUid = targetUid,
                        isQualcommPal = socProfile.useQualcommPalPreWarm
                    ) { spec ->
                        onSniffedSpecChanged(spec)
                    }
                }

                reconfigureCaptureAndHardwareLocked(
                    sourceSampleRate = curRate,
                    sourceTag = if (targetUid != null) "scope:single:$targetUid" else "scope:global",
                    forceRebind = true,
                    targetUidOverride = targetUid
                )
            }
        }
    }

    fun resolveEffectiveTargetUid(): Int? {
        return when (_captureScopeMode.value) {
            CaptureScopeMode.GLOBAL_MEDIA -> null
            CaptureScopeMode.SINGLE_APP -> _selectedApp.value?.uid?.takeIf { it > 0 }
        }
    }

    /**
     * Resolves the best player package to launch from the quick-launch button:
     * Single-App locked app -> Shizuku live-tracked app -> first installed recommended audio app.
     */
    fun resolvePreferredLaunchTarget(): Pair<String, String>? {
        if (_captureScopeMode.value == CaptureScopeMode.SINGLE_APP) {
            _selectedApp.value?.let { return it.packageName to it.label }
        }
        _trackedPlayer.value?.let { return it.packageName to it.label }
        _bridgeState.value.sourceSpec?.let { spec ->
            val pkg = spec.packageName
            if (!pkg.isNullOrBlank()) {
                return pkg to (spec.appLabel ?: pkg)
            }
        }
        val firstAudio = _installedApps.value.firstOrNull { it.isRecommendedAudio }
        if (firstAudio != null) {
            return firstAudio.packageName to firstAudio.label
        }
        return null
    }

    private fun applyKeepAliveForCurrentTargets() {
        val extras = buildList {
            _selectedApp.value?.packageName?.let { add(it) }
            _trackedPlayer.value?.packageName?.let { add(it) }
        }
        applyKeepAliveForPackages(extras)
    }

    private fun applyKeepAliveForPackages(extraPackages: List<String>) {
        if (!shizukuController.state.value.isReady) return
        val pending = extraPackages.filter { it.isNotBlank() && keepAliveAppliedPackages.add(it) }
        val needSelf = keepAliveAppliedPackages.add(appContext.packageName)
        if (!needSelf && pending.isEmpty()) return
        shizukuController.applyBackgroundKeepAlive(pending)
    }

    // -------------------------------------------------------------------------
    // SoC-Gated Qualcomm PAL DeepBuffer Guard Helpers
    // -------------------------------------------------------------------------

    private suspend fun ensurePalPreWarmedIfNeeded(preWarmMs: Long) {
        if (socProfile.useQualcommPalPreWarm) {
            deepBufferGuard.ensureStartedAndPreWarmed(preWarmMs)
        }
    }

    private fun stopPalGuardIfNeeded() {
        if (socProfile.useQualcommPalPreWarm) {
            deepBufferGuard.stop()
        }
    }

    private fun isLowLatencyMode(): Boolean = _bufferDurationMs.value <= LOW_LATENCY_THRESHOLD_MS

    // -------------------------------------------------------------------------
    // System Media Volume Guard (prevents 0/150 post-AudioFlinger silence)
    // -------------------------------------------------------------------------

    private fun readSystemMusicVolume(): SystemVolumeState {
        return runCatching {
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            SystemVolumeState(current = cur, max = max)
        }.getOrDefault(SystemVolumeState())
    }

    fun refreshSystemMusicVolume() {
        _systemMusicVolume.value = readSystemMusicVolume()
    }

    fun maximizeSystemMusicVolume() {
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                _systemMusicVolume.value = SystemVolumeState(current = max, max = max)
                appendLog(1, "KT", "已将系统媒体音量恢复至满格 ($max/$max · 0dB 数字位深无损)，请用下方 DAC 硬件音量调节响度")
            }
        }.onFailure { e ->
            appendLog(2, "KT", "自动拉满系统媒体音量受限 (${e.message})，请按手机音量+键调至满格")
        }
    }

    // -------------------------------------------------------------------------
    // Shizuku Controls & Diagnostic Snapshot
    // -------------------------------------------------------------------------

    fun refreshShizukuStatus() {
        shizukuController.refreshStatus()
    }

    fun requestShizukuPermission() {
        shizukuController.requestPermission()
    }

    fun setOperatingMode(mode: BridgeOperatingMode) {
        if (_operatingMode.value == mode) return
        if (mode == BridgeOperatingMode.SHIZUKU_AUTO && !shizukuController.state.value.isReady) {
            appendLog(2, "SHIZUKU", "Shizuku 尚未就绪，正在检查或请求 Shizuku 授权...")
            shizukuController.requestPermission()
        }
        _operatingMode.value = mode
        appendLog(1, "KT", "运行模式已切换为: ${mode.shortLabel} (${mode.desc})")

        if (_bridgeState.value.active) {
            scope.launch {
                reconfigureMutex.withLock {
                    if (!_bridgeState.value.active) return@withLock
                    val targetUid = resolveEffectiveTargetUid()
                    if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuController.state.value.isReady) {
                        if (_remoteSubmixProfile.value == null) {
                            _remoteSubmixProfile.value = specSniffer.probeRemoteSubmixProfile()
                        }
                        _bridgeState.update { it.copy(operatingMode = mode) }
                        specSniffer.start(
                            targetUid = targetUid,
                            isQualcommPal = socProfile.useQualcommPalPreWarm
                        ) { spec ->
                            onSniffedSpecChanged(spec)
                        }
                    } else {
                        specSniffer.stop()
                        _bridgeState.update { it.copy(operatingMode = mode) }
                        reconfigureCaptureAndHardwareLocked(
                            sourceSampleRate = _manualSampleRate.value,
                            sourceTag = "manual:${_manualSampleRate.value}Hz"
                        )
                    }
                }
            }
        }
    }

    fun selectManualSampleRate(rateHz: Int) {
        val normalized = if (rateHz == 48000) 48000 else 44100
        _manualSampleRate.value = normalized
        val previewPlan = computeEffectiveRatePlan(
            sourceRate = normalized,
            profile = activeSession?.profile,
            submixProfile = _remoteSubmixProfile.value,
            allowHiResSubmixNative = !hiResSubmixCaptureFailed
        )
        appendLog(
            1,
            "KT",
            "手动采样率基准已设为 ${normalized / 1000.0} kHz -> ${previewPlan.formatPathSummary("S${_preferredBridgeBitDepth.value}")}"
        )

        if (_bridgeState.value.active) {
            if (_operatingMode.value == BridgeOperatingMode.SHIZUKU_AUTO) {
                specSniffer.stop()
                _operatingMode.value = BridgeOperatingMode.NON_ROOT_MANUAL
                appendLog(1, "KT", "已切换为无 Root 手动选频模式（可随时切回 Shizuku 自动切频）")
            }
            scope.launch {
                reconfigureMutex.withLock {
                    if (_bridgeState.value.active) {
                        reconfigureCaptureAndHardwareLocked(
                            sourceSampleRate = normalized,
                            sourceTag = "manual:${normalized}Hz"
                        )
                    }
                }
            }
        }
    }

    /**
     * One-tap clean USB/audio resync without unplugging (preserves hardware volume dB).
     */
    fun quickResyncBridge() {
        if (!_bridgeState.value.active) return
        scope.launch {
            reconfigureMutex.withLock {
                if (!_bridgeState.value.active) return@withLock
                val curRate = _bridgeState.value.sourceSpec?.sampleRate ?: _manualSampleRate.value
                appendLog(1, "KT", "正在执行无拔插无感重同步 (${curRate} Hz)...")
                reconfigureCaptureAndHardwareLocked(
                    sourceSampleRate = curRate,
                    sourceTag = "manual-resync",
                    forceRebind = true
                )
            }
        }
    }

    fun dumpDiagnosticSnapshot(onReady: ((String) -> Unit)? = null) {
        scope.launch {
            val sb = StringBuilder(4096)
            val tel = NativeUsbAudioBridge.getTelemetry()
            val bs = _bridgeState.value
            val sysVol = readSystemMusicVolume()
            val targetUid = resolveEffectiveTargetUid() ?: _trackedPlayer.value?.uid ?: -1

            sb.append("========== BitPerfect USB Bridge v${BuildConfig.VERSION_NAME} 诊断快照 ==========\n")
            sb.append("Platform: ${socProfile.diagnosticSummary()}\n")
            sb.append("Scope: ${_captureScopeMode.value.name} (selected=${_selectedApp.value?.packageName ?: "GLOBAL"}, tracked=${_trackedPlayer.value?.packageName ?: "none"})\n")
            sb.append("OperatingMode: ${_operatingMode.value.shortLabel} | Shizuku: ${shizukuState.value.shortBadge} | SysMusicVol: ${sysVol.current}/${sysVol.max}\n")
            sb.append("r_submix: ${_remoteSubmixProfile.value?.rawSummary ?: "未探测 (非 Shizuku 模式)"}\n")
            sb.append("SignalPath: ${bs.formatPathSummary() ?: "未激活"} | status=${bs.statusMessage}\n")
            sb.append("Telemetry: streaming=${tel.streaming} | SPSC=${"%.1f".format(tel.bufferedMs)}/${tel.targetBufferMs}ms (min=${"%.1f".format(tel.minBufferedMs)}ms) | USB(pkts=${tel.packetsSubmitted}, err=${tel.transferErrors}, underrun=${tel.underruns}, maxGap=${"%.1f".format(tel.maxUsbGapMs)}ms) | zeroDrops(runs=${tel.zeroDropoutRuns}, blks=${tel.zeroDropoutBlocks}) | drift(+${tel.driftInsertedFrames}/-${tel.driftDroppedFrames}) | peak=${"%.1f".format(tel.peakDbfs)}dBFS\n")

            if (shizukuController.state.value.isReady) {
                val shizukuDump = shizukuController.dumpDiagnosticsSnapshot(targetUid)
                if (!shizukuDump.isNullOrBlank()) {
                    sb.append(shizukuDump).append('\n')
                }
            } else {
                sb.append("(提示: 当前未连接 Shizuku，仅导出本应用与 SoC 遥测；授权 Shizuku 后可同步抓取 AudioFlinger / dumpsys audio / r_submix 快照)\n")
            }

            val fullText = sb.toString()
            fullText.lineSequence().take(100).forEach { line ->
                if (line.isNotBlank()) appendLog(0, "DIAG", line)
            }
            onReady?.invoke(fullText)
        }
    }

    // -------------------------------------------------------------------------
    // USB Discovery & Connection (Deferred Claim + Progressive Safe Detach)
    // -------------------------------------------------------------------------

    fun refreshDevices() {
        val audioDevices = usbManager.deviceList.values
            .mapNotNull { dev ->
                val uniqueAudioIfIds = collectUniqueAudioInterfaces(dev)
                if (uniqueAudioIfIds.isEmpty()) return@mapNotNull null
                DiscoveredUsbAudioDevice(
                    device = dev,
                    displayName = deviceDisplayName(dev),
                    vidPid = "%04X:%04X".format(dev.vendorId, dev.productId),
                    hasPermission = usbManager.hasPermission(dev),
                    audioInterfaceCount = uniqueAudioIfIds.size
                )
            }
            .sortedBy { it.displayName }

        _discoveredDevices.value = audioDevices
        appendLog(1, "KT", "扫描 USB 总线: 发现 ${audioDevices.size} 台 USB Audio DAC")

        // Auto-arm single already-permitted USB DAC on launch
        if (activeSession == null && armedDevice == null && audioDevices.size == 1 && audioDevices[0].hasPermission) {
            onUsbPermissionReady(audioDevices[0].device)
        }
    }

    fun requestConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            appendLog(1, "KT", "已有 USB 权限: ${deviceDisplayName(device)}")
            onUsbPermissionReady(device)
        } else {
            appendLog(1, "KT", "正在请求 USB 权限: ${deviceDisplayName(device)}...")
            _connectionState.value = DacConnectionState.RequestingPermission(deviceDisplayName(device))
            val explicitIntent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                device.deviceId,
                explicitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, pendingIntent)
        }
    }

    fun claimArmedUsbNow() {
        val dev = armedDevice ?: return
        scope.launch {
            reconfigureMutex.withLock {
                if (activeSession == null) {
                    ensurePalPreWarmedIfNeeded(260L)
                }
                openUsbSessionLocked(dev)
            }
        }
    }

    private fun onUsbPermissionReady(device: UsbDevice) {
        armedDevice = device
        val name = deviceDisplayName(device)
        val vidPid = "%04X:%04X".format(device.vendorId, device.productId)
        _connectionState.value = DacConnectionState.ArmedStandby(
            device = device,
            displayName = name,
            vidPid = vidPid
        )
        val stratNote = if (socProfile.useQualcommPalPreWarm) "PAL 预热与两阶段接管" else "通用纯净两阶段接管"
        appendLog(
            1,
            "KT",
            "$name ($vidPid) 已就绪待命 —— 点击「启动直通」将自动完成 $stratNote"
        )
    }

    private fun openUsbSessionLocked(device: UsbDevice, initialVolumeDb: Double = 0.0): ActiveUsbSession? {
        activeSession?.let { return it }

        val name = deviceDisplayName(device)
        _connectionState.value = DacConnectionState.Connecting(name)

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            val msg = "UsbManager.openDevice() 失败: $name"
            appendLog(3, "KT", msg)
            _connectionState.value = DacConnectionState.Error(msg)
            return null
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            val msg = "无效的 USB 文件描述符 fd=$fd ($name)"
            appendLog(3, "KT", msg)
            connection.close()
            _connectionState.value = DacConnectionState.Error(msg)
            return null
        }
        appendLog(
            1,
            "KT",
            "已打开 UsbDeviceConnection: $name (fd=$fd)，执行 C++ 渐进式两阶段接管 (IF#2 -> 15ms -> IF#0，跳过 IF#1 麦克风)..."
        )

        val inspectResult = NativeUsbAudioBridge.openAndInspect(
            fd = fd,
            vendorId = device.vendorId,
            productId = device.productId,
            fallbackName = name,
            initialVolumeDb = initialVolumeDb
        )

        return when (inspectResult) {
            is InspectResult.Success -> {
                val session = ActiveUsbSession(
                    device = device,
                    connection = connection,
                    profile = inspectResult.profile
                )
                activeSession = session
                armedDevice = device
                lastAppliedVolumeDb = initialVolumeDb
                _volumeDb.value = initialVolumeDb
                NativeUsbAudioBridge.setBufferDurationMs(_bufferDurationMs.value)

                if (!_bridgeState.value.active) {
                    val idleTag = if (AppLanguageManager.isChinese(appContext)) "待机中" else "Standby"
                    UsbBridgeService.startConnectedDevice(
                        appContext,
                        "${inspectResult.profile.productName} (${inspectResult.profile.uacVersion}) · $idleTag"
                    )
                }
                _connectionState.value = DacConnectionState.Connected(
                    device = device,
                    profile = inspectResult.profile,
                    fullReport = inspectResult.report
                )
                startTelemetryPolling()
                refreshDevices()
                session
            }
            is InspectResult.Failure -> {
                appendLog(3, "KT", "USB 描述符解析失败: ${inspectResult.error}")
                runCatching { connection.close() }
                _connectionState.value = DacConnectionState.Error(inspectResult.error)
                null
            }
        }
    }

    // -------------------------------------------------------------------------
    // MediaProjection Bridge Lifecycle (Universal Multi-Player & Multi-SoC)
    // -------------------------------------------------------------------------

    fun onMediaProjectionGranted(resultCode: Int, resultData: Intent) {
        if (activeSession == null && armedDevice == null) {
            appendLog(2, "KT", "请先接入并授权 USB DAC 后再启动直通")
            return
        }
        appendLog(1, "KT", "已获得 MediaProjection 授权，正在启动前台音频直通服务...")
        val prepText = if (AppLanguageManager.isChinese(appContext)) {
            "BitPerfect USB 独占直通准备中..."
        } else {
            "Preparing BitPerfect USB exclusive bridge..."
        }
        UsbBridgeService.startMediaProjectionCapture(
            context = appContext,
            resultCode = resultCode,
            resultData = resultData,
            statusText = prepText
        )
    }

    private fun onMediaProjectionReady(mediaProjection: MediaProjection) {
        scope.launch {
            reconfigureMutex.withLock {
                specSniffer.stop()
                capturePipeline.stopCapture()
                if (activeSession != null) {
                    NativeUsbAudioBridge.stopStream()
                }
                activeMediaProjection = mediaProjection
                hiResSubmixCaptureFailed = false

                val scopeMode = _captureScopeMode.value
                val targetUid = resolveEffectiveTargetUid()
                if (scopeMode == CaptureScopeMode.SINGLE_APP && targetUid != null) {
                    val sel = _selectedApp.value
                    _trackedPlayer.value = sel?.let {
                        ResolvedUidApp(it.uid, it.packageName, it.label, it.icon)
                    }
                    appendLog(1, "KT", "单应用锁定模式: ${sel?.label ?: "UID=$targetUid"} (UID=$targetUid)")
                } else {
                    _trackedPlayer.value = null
                    appendLog(1, "KT", "全局媒体/音游自动追踪模式已启用 (捕获所有第三方音乐/音游音频，自动排除本 App)")
                }

                refreshSystemMusicVolume()
                val sysVol = _systemMusicVolume.value
                if (!sysVol.isFull) {
                    appendLog(
                        2,
                        "KT",
                        "当前系统媒体音量为 ${sysVol.current}/${sysVol.max}：录屏内录建议调至满格 (0dB 无损位深)，再用下方 DAC 硬件音量控制响度"
                    )
                }

                val mode = _operatingMode.value
                val shizukuReady = shizukuController.state.value.isReady

                var initialSpec = DetectedAudioSpec(
                    sampleRate = _manualSampleRate.value,
                    bitDepth = 24,
                    sourceTag = "manual:${_manualSampleRate.value}Hz",
                    uid = targetUid ?: -1,
                    packageName = if (scopeMode == CaptureScopeMode.SINGLE_APP) _selectedApp.value?.packageName else null,
                    appLabel = if (scopeMode == CaptureScopeMode.SINGLE_APP) _selectedApp.value?.label else null
                )

                if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuReady) {
                    applyKeepAliveForCurrentTargets()
                    if (_remoteSubmixProfile.value == null) {
                        _remoteSubmixProfile.value = specSniffer.probeRemoteSubmixProfile()
                    }
                    val quickDump = shizukuController.dumpAudioPlayback(targetUid ?: -1)
                    if (!quickDump.isNullOrBlank()) {
                        val detected = specSniffer.parseBestActiveTrack(quickDump, targetUid)
                        if (detected != null) {
                            initialSpec = detected
                            if (detected.uid > 0) {
                                _trackedPlayer.value = InstalledAppScanner.resolveUidToApp(appContext, detected.uid)
                            }
                            if (!detected.packageName.isNullOrBlank()) {
                                applyKeepAliveForPackages(listOf(detected.packageName))
                            }
                            appendLog(
                                1,
                                "SNIFF",
                                "Shizuku 初始检测到活跃音轨: [${detected.appLabel ?: "UID=${detected.uid}"}] ${detected.sampleRate} Hz"
                            )
                        }
                    }
                }

                // Strictly Qualcomm-only PAL pre-warm (skipped on XRING / Exynos / MediaTek / Tensor)
                ensurePalPreWarmedIfNeeded(preWarmMs = if (activeSession == null) 260L else 0L)

                var initialPlan = computeEffectiveRatePlan(
                    sourceRate = initialSpec.sampleRate,
                    profile = activeSession?.profile,
                    submixProfile = _remoteSubmixProfile.value,
                    allowHiResSubmixNative = !hiResSubmixCaptureFailed
                )
                val lowLatency = isLowLatencyMode()

                if (activeSession == null) {
                    val targetDevice = armedDevice
                    if (targetDevice == null) {
                        appendLog(3, "KT", "未找到已授权的 USB DAC 设备")
                        return@withLock
                    }

                    appendLog(
                        1,
                        "KT",
                        "[Step 1/2] 先启动 MediaProjection AudioRecord @ ${initialPlan.effectiveSampleRate} Hz (PCM_FLOAT)..."
                    )

                    var capRes = capturePipeline.startCapture(
                        mediaProjection = mediaProjection,
                        targetUid = targetUid,
                        sampleRate = initialPlan.effectiveSampleRate,
                        lowLatencyMode = lowLatency,
                        onFatalError = { err ->
                            appendLog(3, "CAP", "音频捕获中止: $err")
                            _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
                        }
                    )

                    // Automatic fallback if an OEM ROM advertised >48k r_submix but rejected AudioRecord init
                    if (capRes.isFailure && initialPlan.isHiResSubmixNative) {
                        hiResSubmixCaptureFailed = true
                        initialPlan = computeEffectiveRatePlan(
                            sourceRate = initialSpec.sampleRate,
                            profile = activeSession?.profile,
                            submixProfile = _remoteSubmixProfile.value,
                            allowHiResSubmixNative = false
                        )
                        appendLog(
                            2,
                            "KT",
                            "系统 r_submix 拒绝高频直采，已安全回退整数分频基准 ${initialPlan.effectiveSampleRate} Hz"
                        )
                        capRes = capturePipeline.startCapture(
                            mediaProjection = mediaProjection,
                            targetUid = targetUid,
                            sampleRate = initialPlan.effectiveSampleRate,
                            lowLatencyMode = lowLatency,
                            onFatalError = { err ->
                                appendLog(3, "CAP", "音频捕获中止: $err")
                                _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
                            }
                        )
                    }

                    val activeCap = capRes.getOrElse { err ->
                        val msg = "启动 AudioRecord 失败: ${err.message}"
                        appendLog(3, "KT", msg)
                        _bridgeState.value = AudioBridgeState(
                            active = false,
                            statusMessage = msg,
                            targetUid = targetUid,
                            captureScopeMode = scopeMode,
                            operatingMode = mode
                        )
                        return@withLock
                    }

                    // Give Android AudioPolicyManager 150ms to stabilize r_submix / DuplicatingThread BEFORE detaching USB
                    delay(150L)

                    appendLog(1, "KT", "[Step 2/2] r_submix 管线已稳态运行，现在后置两阶段接管 USB DAC...")

                    val session = openUsbSessionLocked(targetDevice)
                    if (session == null) {
                        capturePipeline.stopCapture()
                        _bridgeState.value = AudioBridgeState(
                            active = false,
                            statusMessage = "接管 USB DAC 失败",
                            targetUid = targetUid,
                            captureScopeMode = scopeMode,
                            operatingMode = mode
                        )
                        return@withLock
                    }

                    val finalPlan = computeEffectiveRatePlan(
                        sourceRate = initialSpec.sampleRate,
                        profile = session.profile,
                        submixProfile = _remoteSubmixProfile.value,
                        allowHiResSubmixNative = !hiResSubmixCaptureFailed
                    )
                    val preferredBits = _preferredBridgeBitDepth.value
                    val hwRes = NativeUsbAudioBridge.prepareCaptureStream(
                        sampleRate = finalPlan.effectiveSampleRate,
                        preferredBitDepth = preferredBits
                    )
                    val hw = hwRes.getOrElse { err ->
                        val msg = "DAC 硬件配置失败: ${err.message}"
                        appendLog(3, "KT", msg)
                        capturePipeline.stopCapture()
                        telemetryJob?.cancel()
                        telemetryJob = null
                        NativeUsbAudioBridge.close(restoreUnityVolume = true)
                        runCatching { session.connection.close() }
                        activeSession = null
                        _telemetry.value = StreamTelemetry()
                        val name = deviceDisplayName(targetDevice)
                        val vidPid = "%04X:%04X".format(targetDevice.vendorId, targetDevice.productId)
                        _connectionState.value = DacConnectionState.ArmedStandby(targetDevice, name, vidPid)
                        _bridgeState.value = AudioBridgeState(
                            active = false,
                            statusMessage = msg,
                            targetUid = targetUid,
                            captureScopeMode = scopeMode,
                            operatingMode = mode
                        )
                        return@withLock
                    }

                    val finalCap = if (hw.dacSampleRate != activeCap.sampleRate) {
                        capturePipeline.stopCapture()
                        capturePipeline.startCapture(
                            mediaProjection = mediaProjection,
                            targetUid = targetUid,
                            sampleRate = hw.dacSampleRate,
                            lowLatencyMode = lowLatency,
                            onFatalError = { err ->
                                appendLog(3, "CAP", "音频捕获中止: $err")
                                _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
                            }
                        ).getOrNull() ?: activeCap
                    } else {
                        activeCap
                    }

                    refreshSystemMusicVolume()

                    val nextState = AudioBridgeState(
                        active = true,
                        statusMessage = if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuReady) {
                            "Shizuku 自动直通中 · 可切往任意音乐或音游播放"
                        } else {
                            "无 Root 独占直通中 · 可切往任意音乐或音游播放"
                        },
                        targetUid = targetUid,
                        captureScopeMode = scopeMode,
                        operatingMode = mode,
                        sourceSpec = initialSpec,
                        ratePlan = finalPlan.copy(effectiveSampleRate = hw.dacSampleRate),
                        dacHardware = hw,
                        captureConfig = finalCap,
                        reconfigureCount = 1
                    )
                    _bridgeState.value = nextState
                    resetAutoHealWindow()
                    nextState.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
                } else {
                    _bridgeState.value = AudioBridgeState(
                        active = true,
                        targetUid = targetUid,
                        captureScopeMode = scopeMode,
                        operatingMode = mode,
                        statusMessage = "正在锁定 DAC 与 MediaProjection..."
                    )
                    reconfigureCaptureAndHardwareLocked(
                        sourceSampleRate = initialSpec.sampleRate,
                        sourceTag = initialSpec.sourceTag,
                        forceRebind = true,
                        trackSpec = initialSpec,
                        targetUidOverride = targetUid
                    )
                }

                if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuReady) {
                    specSniffer.start(
                        targetUid = targetUid,
                        isQualcommPal = socProfile.useQualcommPalPreWarm
                    ) { spec ->
                        onSniffedSpecChanged(spec)
                    }
                }
            }
        }
    }

    private fun onSniffedSpecChanged(spec: DetectedAudioSpec) {
        scope.launch {
            reconfigureMutex.withLock {
                if (!_bridgeState.value.active) return@withLock
                if (_operatingMode.value != BridgeOperatingMode.SHIZUKU_AUTO) return@withLock
                val session = activeSession ?: return@withLock

                if (spec.uid > 0) {
                    _trackedPlayer.value = InstalledAppScanner.resolveUidToApp(appContext, spec.uid)
                }
                if (!spec.packageName.isNullOrBlank()) {
                    applyKeepAliveForPackages(listOf(spec.packageName))
                }

                val newPlan = computeEffectiveRatePlan(
                    sourceRate = spec.sampleRate,
                    profile = session.profile,
                    submixProfile = _remoteSubmixProfile.value,
                    allowHiResSubmixNative = !hiResSubmixCaptureFailed
                )
                val curSpec = _bridgeState.value.sourceSpec
                val curPlan = _bridgeState.value.ratePlan
                val curHw = _bridgeState.value.dacHardware

                // Qualcomm-only proactive PAL rebind when a new Hi-Res (>48k) track starts
                val isQualcommNewHiResTrack = socProfile.useQualcommPalPreWarm &&
                    spec.sampleRate > 48000 &&
                    (spec.piid != curSpec?.piid || spec.sampleRate != curSpec?.sampleRate)

                if (!isQualcommNewHiResTrack &&
                    curPlan != null && curHw != null &&
                    curPlan.effectiveSampleRate == newPlan.effectiveSampleRate &&
                    capturePipeline.isRunning
                ) {
                    val appTag = spec.appLabel ?: spec.packageName ?: "Media"
                    val updated = _bridgeState.value.copy(
                        sourceSpec = spec,
                        ratePlan = newPlan,
                        statusMessage = "正在追踪: $appTag · ${"%.1f".format(newPlan.effectiveSampleRate / 1000.0)} kHz"
                    )
                    _bridgeState.value = updated
                    updated.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
                    appendLog(
                        1,
                        "SNIFF",
                        "[$appTag] 同族采样率无缝锁定 (${spec.sampleRate} Hz -> ${newPlan.effectiveSampleRate} Hz)"
                    )
                    return@withLock
                }

                if (isQualcommNewHiResTrack) {
                    appendLog(
                        1,
                        "SNIFF",
                        "检测到 [${spec.appLabel ?: "Hi-Res"}] 新音轨 (${spec.sampleRate} Hz, piid=${spec.piid}) -> 执行高通 PAL 无卡顿重同步"
                    )
                }

                reconfigureCaptureAndHardwareLocked(
                    sourceSampleRate = spec.sampleRate,
                    sourceTag = spec.sourceTag,
                    forceRebind = isQualcommNewHiResTrack,
                    trackSpec = spec
                )
            }
        }
    }

    private suspend fun reconfigureCaptureAndHardwareLocked(
        sourceSampleRate: Int,
        sourceTag: String,
        forceRebind: Boolean = false,
        trackSpec: DetectedAudioSpec? = null,
        targetUidOverride: Int? = resolveEffectiveTargetUid()
    ) {
        val curSession = activeSession
        val targetDevice = curSession?.device ?: armedDevice ?: return
        val mp = activeMediaProjection ?: return
        val scopeMode = _captureScopeMode.value
        val targetUid = targetUidOverride
        val preferredBits = _preferredBridgeBitDepth.value
        val lowLatency = isLowLatencyMode()

        var plan = computeEffectiveRatePlan(
            sourceRate = sourceSampleRate,
            profile = curSession?.profile,
            submixProfile = _remoteSubmixProfile.value,
            allowHiResSubmixNative = !hiResSubmixCaptureFailed
        )

        val effectiveSpec = trackSpec ?: DetectedAudioSpec(
            sampleRate = sourceSampleRate,
            bitDepth = 24,
            sourceTag = sourceTag,
            uid = targetUid ?: _bridgeState.value.sourceSpec?.uid ?: -1,
            packageName = _trackedPlayer.value?.packageName ?: _bridgeState.value.sourceSpec?.packageName,
            appLabel = _trackedPlayer.value?.label ?: _bridgeState.value.sourceSpec?.appLabel
        )

        val curCap = _bridgeState.value.captureConfig
        val curHw = _bridgeState.value.dacHardware
        val captureUnchanged = curCap != null &&
            capturePipeline.isRunning &&
            curCap.sampleRate == plan.effectiveSampleRate &&
            curCap.lowLatencyMode == lowLatency &&
            curCap.targetUid == targetUid

        // Case 1: Same effective rate, latency mode, scope & bit-depth
        if (!forceRebind && curSession != null && captureUnchanged && curHw != null && curHw.dacBitResolution == preferredBits) {
            val updated = _bridgeState.value.copy(
                active = true,
                targetUid = targetUid,
                captureScopeMode = scopeMode,
                operatingMode = _operatingMode.value,
                sourceSpec = effectiveSpec,
                ratePlan = plan.copy(effectiveSampleRate = curHw.dacSampleRate),
                statusMessage = if (_operatingMode.value == BridgeOperatingMode.SHIZUKU_AUTO) {
                    "Shizuku 自动直通中 · 无缝锁定 ${curHw.dacSampleRate / 1000.0} kHz"
                } else {
                    "无 Root 独占直通中 · 已锁定 ${curHw.dacSampleRate / 1000.0} kHz"
                }
            )
            _bridgeState.value = updated
            updated.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
            return
        }

        // Case 2: Only DAC bit-depth changed (24-bit <-> 32-bit) at same rate & capture config -> keep AudioRecord alive!
        if (!forceRebind && curSession != null && captureUnchanged && curHw != null && curHw.dacBitResolution != preferredBits) {
            appendLog(
                1,
                "KT",
                "原地切换 DAC 传输位深 (保持 AudioRecord 不断流): ${curHw.dacSampleRate} Hz -> ${preferredBits}-bit"
            )
            val hwRes = NativeUsbAudioBridge.prepareCaptureStream(
                sampleRate = curHw.dacSampleRate,
                preferredBitDepth = preferredBits
            )
            val hw = hwRes.getOrElse { err ->
                val msg = "DAC 位深切换失败: ${err.message}"
                appendLog(3, "KT", msg)
                _bridgeState.update { it.copy(statusMessage = msg) }
                return
            }
            val nextState = _bridgeState.value.copy(
                dacHardware = hw,
                reconfigureCount = _bridgeState.value.reconfigureCount + 1
            )
            _bridgeState.value = nextState
            nextState.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
            return
        }

        // Case 3: Cross-family rate switch, scope switch, latency mode switch, or Auto-Heal rebind
        val savedVolumeDb = if (curSession != null) _volumeDb.value else 0.0
        appendLog(
            1,
            "KT",
            "执行安全切频重同步 [${_operatingMode.value.shortLabel}]: " +
                "${plan.formatPathSummary("S${preferredBits}")} | 保持硬件音量=${"%.1f".format(savedVolumeDb)}dB"
        )

        capturePipeline.stopCapture()

        if (curSession != null) {
            stopPalGuardIfNeeded()
            telemetryJob?.cancel()
            telemetryJob = null
            NativeUsbAudioBridge.close(restoreUnityVolume = false)
            runCatching { curSession.connection.close() }
            activeSession = null
            armedDevice = targetDevice
            delay(socProfile.rebindSettleDelayMs)
        }

        ensurePalPreWarmedIfNeeded(260L)

        var capRes = capturePipeline.startCapture(
            mediaProjection = mp,
            targetUid = targetUid,
            sampleRate = plan.effectiveSampleRate,
            lowLatencyMode = lowLatency,
            onFatalError = { err ->
                appendLog(3, "CAP", "音频捕获中止: $err")
                _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
            }
        )

        if (capRes.isFailure && plan.isHiResSubmixNative) {
            hiResSubmixCaptureFailed = true
            plan = computeEffectiveRatePlan(
                sourceRate = sourceSampleRate,
                profile = curSession?.profile,
                submixProfile = _remoteSubmixProfile.value,
                allowHiResSubmixNative = false
            )
            appendLog(
                2,
                "KT",
                "系统 r_submix 拒绝高频直采，已回退安全整数分频基准 ${plan.effectiveSampleRate} Hz"
            )
            capRes = capturePipeline.startCapture(
                mediaProjection = mp,
                targetUid = targetUid,
                sampleRate = plan.effectiveSampleRate,
                lowLatencyMode = lowLatency,
                onFatalError = { err ->
                    appendLog(3, "CAP", "音频捕获中止: $err")
                    _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
                }
            )
        }

        val activeCap = capRes.getOrElse { err ->
            val msg = "AudioRecord 重建失败: ${err.message}"
            appendLog(3, "KT", msg)
            _bridgeState.update { it.copy(statusMessage = msg) }
            return
        }

        delay(150L)

        val newSession = openUsbSessionLocked(targetDevice, initialVolumeDb = savedVolumeDb)
        if (newSession == null) {
            capturePipeline.stopCapture()
            _bridgeState.update { it.copy(statusMessage = "切频后重新接管 USB DAC 失败") }
            return
        }

        val finalPlan = computeEffectiveRatePlan(
            sourceRate = sourceSampleRate,
            profile = newSession.profile,
            submixProfile = _remoteSubmixProfile.value,
            allowHiResSubmixNative = !hiResSubmixCaptureFailed
        )
        val hwResult = NativeUsbAudioBridge.prepareCaptureStream(
            sampleRate = finalPlan.effectiveSampleRate,
            preferredBitDepth = preferredBits
        )
        val hw = hwResult.getOrElse { err ->
            val msg = "DAC 硬件切频失败 (${finalPlan.effectiveSampleRate}Hz): ${err.message}"
            appendLog(3, "KT", msg)
            capturePipeline.stopCapture()
            _bridgeState.update { it.copy(statusMessage = msg) }
            return
        }

        refreshSystemMusicVolume()

        val prevCount = _bridgeState.value.reconfigureCount
        val nextState = _bridgeState.value.copy(
            active = true,
            targetUid = targetUid,
            captureScopeMode = scopeMode,
            operatingMode = _operatingMode.value,
            sourceSpec = effectiveSpec,
            ratePlan = finalPlan.copy(effectiveSampleRate = hw.dacSampleRate),
            dacHardware = hw,
            captureConfig = activeCap,
            reconfigureCount = prevCount + 1,
            statusMessage = if (_operatingMode.value == BridgeOperatingMode.SHIZUKU_AUTO) {
                "Shizuku 自动直通中 · 可切往任意音乐或音游播放"
            } else {
                "无 Root 独占直通中 · 可切往任意音乐或音游播放"
            }
        )
        _bridgeState.value = nextState
        resetAutoHealWindow()
        nextState.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
    }

    fun setPreferredBridgeBitDepth(bitDepth: Int) {
        val normalized = if (bitDepth == 32) 32 else 24
        if (_preferredBridgeBitDepth.value == normalized) return
        _preferredBridgeBitDepth.value = normalized
        appendLog(
            1,
            "KT",
            "DAC 传输位深已切换为: ${if (normalized == 32) "32-bit (S32_LE / Alt#3)" else "24-bit (S24_3LE / Alt#2)"}"
        )
        if (_bridgeState.value.active) {
            scope.launch {
                reconfigureMutex.withLock {
                    if (_bridgeState.value.active) {
                        val curRate = _bridgeState.value.sourceSpec?.sampleRate ?: _manualSampleRate.value
                        reconfigureCaptureAndHardwareLocked(curRate, "bitdepth-switch")
                    }
                }
            }
        }
    }

    fun stopAudioBridge() {
        scope.launch {
            reconfigureMutex.withLock {
                stopAudioBridgeLocked(releaseUsbToStandby = true)
            }
        }
    }

    private suspend fun stopAudioBridgeLocked(releaseUsbToStandby: Boolean) {
        specSniffer.stop()
        capturePipeline.stopCapture()
        val mp = activeMediaProjection
        activeMediaProjection = null
        if (mp != null) {
            runCatching { mp.stop() }
            appendLog(1, "KT", "已释放 MediaProjection 音频捕获会话")
        }

        val session = activeSession
        if (session != null) {
            NativeUsbAudioBridge.stopStream()
            if (session.profile.hasHardwareVolume) {
                NativeUsbAudioBridge.setHardwareVolumeDb(0.0)
                lastAppliedVolumeDb = 0.0
                _volumeDb.value = 0.0
            }

            if (releaseUsbToStandby) {
                stopPalGuardIfNeeded()
                val dev = session.device
                telemetryJob?.cancel()
                telemetryJob = null
                NativeUsbAudioBridge.close(restoreUnityVolume = true)
                runCatching { session.connection.close() }
                activeSession = null
                _telemetry.value = StreamTelemetry()

                UsbBridgeService.stop(appContext)
                delay(280L)
                armedDevice = dev
                val name = deviceDisplayName(dev)
                val vidPid = "%04X:%04X".format(dev.vendorId, dev.productId)
                _connectionState.value = DacConnectionState.ArmedStandby(dev, name, vidPid)
                refreshSystemMusicVolume()
                appendLog(1, "KT", "已停止直通并将 USB 干净归还系统驱动，无需拔插可随时再次启动")
            } else {
                UsbBridgeService.stopMediaProjectionCapture(
                    appContext,
                    "${session.profile.productName} · 待机中"
                )
                appendLog(1, "KT", "已停止直通并复位音频流至 Alt#0 与 0.0 dB")
            }
        } else {
            stopPalGuardIfNeeded()
            UsbBridgeService.stop(appContext)
        }

        if (_captureScopeMode.value == CaptureScopeMode.GLOBAL_MEDIA) {
            _trackedPlayer.value = null
        }
        _bridgeState.value = AudioBridgeState(
            active = false,
            captureScopeMode = _captureScopeMode.value,
            operatingMode = _operatingMode.value,
            statusMessage = "已停止"
        )
    }

    // -------------------------------------------------------------------------
    // Built-in 440Hz Reference Sine Self-Test
    // -------------------------------------------------------------------------

    fun toggleSineTest() {
        scope.launch {
            reconfigureMutex.withLock {
                if (activeSession == null) {
                    val dev = armedDevice
                    if (dev == null) {
                        appendLog(2, "KT", "请先接入并授权 USB DAC 再启动 440Hz 晶振测试音")
                        return@withLock
                    }
                    ensurePalPreWarmedIfNeeded(260L)
                    if (openUsbSessionLocked(dev) == null) return@withLock
                }
                if (_telemetry.value.sineRunning) {
                    NativeUsbAudioBridge.stopStream()
                    updateTelemetryOnce()
                    activeSession?.let {
                        UsbBridgeService.updateStatus(appContext, "${it.profile.productName} · 待机中")
                    }
                } else {
                    if (_bridgeState.value.active) {
                        stopAudioBridgeLocked(releaseUsbToStandby = false)
                    }
                    startSinePresetLocked(_selectedPreset.value)
                }
            }
        }
    }

    fun switchSinePreset(preset: SineTestPreset) {
        _selectedPreset.value = preset
        scope.launch {
            reconfigureMutex.withLock {
                if (activeSession == null) {
                    val dev = armedDevice ?: return@withLock
                    ensurePalPreWarmedIfNeeded(260L)
                    if (openUsbSessionLocked(dev) == null) return@withLock
                }
                if (_bridgeState.value.active) {
                    stopAudioBridgeLocked(releaseUsbToStandby = false)
                }
                startSinePresetLocked(preset)
            }
        }
    }

    private fun startSinePresetLocked(preset: SineTestPreset) {
        val session = activeSession ?: return
        NativeUsbAudioBridge.startSineTest(preset.sampleRate, preset.bitDepth)
            .onSuccess {
                updateTelemetryOnce()
                UsbBridgeService.updateStatus(
                    appContext,
                    "${session.profile.productName} · 440Hz ${preset.label}"
                )
            }
            .onFailure { err ->
                appendLog(3, "KT", "启动 440Hz 测试音失败 (${preset.label}): ${err.message}")
            }
    }

    // -------------------------------------------------------------------------
    // Hardware Volume & Ring Buffer Controls (Supports 60ms Rhythm Game Mode)
    // -------------------------------------------------------------------------

    fun setHardwareVolumeDb(db: Double) {
        val clamped = clampVolumeDb(db)
        _volumeDb.value = clamped
        if (activeSession != null) {
            volumeInputFlow.tryEmit(clamped)
        }
    }

    fun commitHardwareVolumeDb() {
        val targetDb = _volumeDb.value
        scope.launch {
            applyHardwareVolumeDb(targetDb, force = true)
        }
    }

    private fun applyHardwareVolumeDb(db: Double, force: Boolean = false) {
        val session = activeSession ?: return
        if (!session.profile.hasHardwareVolume) return
        val quantized = Math.round(db * 2.0) / 2.0
        if (!force && !lastAppliedVolumeDb.isNaN() && kotlin.math.abs(quantized - lastAppliedVolumeDb) < 0.01) {
            return
        }
        NativeUsbAudioBridge.setHardwareVolumeDb(quantized)
            .onSuccess { lastAppliedVolumeDb = quantized }
            .onFailure { err -> appendLog(2, "KT", "硬件音量调节失败: ${err.message}") }
    }

    private fun clampVolumeDb(db: Double): Double {
        val profile = activeSession?.profile
        val minDb = profile?.volumeMinDb ?: -60.0
        val maxDb = profile?.volumeMaxDb ?: 0.0
        return db.coerceIn(minDb, maxDb)
    }

    fun setBufferDurationMs(ms: Int) {
        val clamped = ms.coerceIn(60, 600)
        val prevLowLatency = _bufferDurationMs.value <= LOW_LATENCY_THRESHOLD_MS
        val nextLowLatency = clamped <= LOW_LATENCY_THRESHOLD_MS
        _bufferDurationMs.value = clamped
        NativeUsbAudioBridge.setBufferDurationMs(clamped)
        updateTelemetryOnce()

        if (_bridgeState.value.active && prevLowLatency != nextLowLatency) {
            appendLog(
                1,
                "KT",
                if (nextLowLatency) {
                    "已切换至 60ms 音游低延迟模式 (10ms AudioRecord + 24ms USB DMA)，正在重配置管线..."
                } else {
                    "已切回 ${clamped}ms Hi-Fi 抗抖动模式 (20ms AudioRecord + 48ms USB DMA)，正在重配置管线..."
                }
            )
            scope.launch {
                reconfigureMutex.withLock {
                    if (_bridgeState.value.active) {
                        val curRate = _bridgeState.value.sourceSpec?.sampleRate ?: _manualSampleRate.value
                        reconfigureCaptureAndHardwareLocked(
                            sourceSampleRate = curRate,
                            sourceTag = "latency-mode:${clamped}ms",
                            forceRebind = true
                        )
                    }
                }
            }
        }
    }

    fun disconnect(reason: String = "用户手动释放") {
        scope.launch {
            disconnectInternal(reason)
        }
    }

    private suspend fun disconnectInternal(reason: String) {
        reconfigureMutex.withLock {
            specSniffer.stop()
            capturePipeline.stopCapture()
            stopPalGuardIfNeeded()
            val mp = activeMediaProjection
            activeMediaProjection = null
            if (mp != null) {
                runCatching { mp.stop() }
            }

            telemetryJob?.cancel()
            telemetryJob = null

            val session = activeSession
            activeSession = null
            armedDevice = null
            lastAppliedVolumeDb = Double.NaN
            _remoteSubmixProfile.value = null
            hiResSubmixCaptureFailed = false

            if (session != null) {
                appendLog(1, "KT", "正在安全释放 USB DAC ($reason): 30ms 总线静默 -> Alt#0 -> 0.00 dB -> 渐进归还 snd-usb-audio...")
                NativeUsbAudioBridge.close(restoreUnityVolume = true)
                runCatching { session.connection.close() }
                delay(280L)
            }

            UsbBridgeService.stop(appContext)
            _telemetry.value = StreamTelemetry()
            if (_captureScopeMode.value == CaptureScopeMode.GLOBAL_MEDIA) {
                _trackedPlayer.value = null
            }
            _bridgeState.value = AudioBridgeState(
                captureScopeMode = _captureScopeMode.value,
                operatingMode = _operatingMode.value
            )
            _connectionState.value = DacConnectionState.Disconnected
            refreshSystemMusicVolume()
            refreshDevices()
        }
    }

    private fun startTelemetryPolling() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (isActive && activeSession != null) {
                updateTelemetryOnce()
                delay(250)
            }
        }
    }

    private fun resetAutoHealWindow() {
        val now = System.currentTimeMillis()
        streamActiveSinceMs = now
        zeroWatchWindowStartMs = now
        zeroWatchWindowBaseEvents = 0L
    }

    private fun updateTelemetryOnce() {
        refreshSystemMusicVolume()
        if (activeSession == null) return
        val tel = NativeUsbAudioBridge.getTelemetry()
        _telemetry.value = tel

        // Automatic Zero-Drop Self-Healing Watchdog (works across all SoCs in both Non-Root & Shizuku modes)
        val bs = _bridgeState.value
        if (!bs.active || !tel.streaming || tel.capturePrefilling) {
            return
        }

        val now = System.currentTimeMillis()
        val totalZeroEvents = tel.zeroDropoutRuns + tel.zeroDropoutBlocks
        if (streamActiveSinceMs == 0L) {
            streamActiveSinceMs = now
            zeroWatchWindowStartMs = now
            zeroWatchWindowBaseEvents = totalZeroEvents
            return
        }
        if (now - streamActiveSinceMs < 2500L) {
            zeroWatchWindowStartMs = now
            zeroWatchWindowBaseEvents = totalZeroEvents
            return
        }
        if (totalZeroEvents < zeroWatchWindowBaseEvents || now - zeroWatchWindowStartMs >= 3500L) {
            zeroWatchWindowStartMs = now
            zeroWatchWindowBaseEvents = totalZeroEvents
            return
        }

        val deltaEvents = totalZeroEvents - zeroWatchWindowBaseEvents
        if (deltaEvents >= 4L && (now - lastAutoHealMs) >= 12_000L) {
            lastAutoHealMs = now
            zeroWatchWindowStartMs = now
            zeroWatchWindowBaseEvents = totalZeroEvents
            appendLog(
                2,
                "AUTO-HEAL",
                "检测到连续静音洞卡顿 (3.5s 内 ΔzeroDrops=$deltaEvents)，自动执行音频管线安全重绑定自愈..."
            )
            scope.launch {
                reconfigureMutex.withLock {
                    if (!_bridgeState.value.active) return@withLock
                    val curRate = _bridgeState.value.sourceSpec?.sampleRate ?: _manualSampleRate.value
                    reconfigureCaptureAndHardwareLocked(
                        sourceSampleRate = curRate,
                        sourceTag = "auto-heal",
                        forceRebind = true,
                        trackSpec = _bridgeState.value.sourceSpec
                    )
                }
            }
        }
    }

    private fun collectUniqueAudioInterfaces(device: UsbDevice): List<UsbInterface> {
        val seenIds = LinkedHashSet<Int>()
        val result = ArrayList<UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val isAudio = iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                (iface.interfaceSubclass == USB_AUDIO_SUBCLASS_CONTROL ||
                    iface.interfaceSubclass == USB_AUDIO_SUBCLASS_STREAMING)
            if (isAudio && seenIds.add(iface.id)) {
                result.add(iface)
            }
        }
        return result
    }

    private fun deviceDisplayName(device: UsbDevice): String {
        val mfg = device.manufacturerName?.trim().orEmpty()
        val prod = device.productName?.trim().orEmpty()
        return when {
            mfg.isNotEmpty() && prod.isNotEmpty() && !prod.startsWith(mfg, ignoreCase = true) -> "$mfg $prod"
            prod.isNotEmpty() -> prod
            else -> "USB Audio (${"%04X:%04X".format(device.vendorId, device.productId)})"
        }
    }

    private fun appendLog(level: Int, source: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            source = source,
            message = message
        )
        when (level) {
            0 -> Log.d(TAG, "[$source] $message")
            1 -> Log.i(TAG, "[$source] $message")
            2 -> Log.w(TAG, "[$source] $message")
            else -> Log.e(TAG, "[$source] $message")
        }
        _logs.update { current ->
            if (current.size >= MAX_LOG_LINES) {
                current.drop(current.size - MAX_LOG_LINES + 1) + entry
            } else {
                current + entry
            }
        }
    }
}
