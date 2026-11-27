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
import com.bitperfect.usbbridge.i18n.AppLanguageManager
import com.bitperfect.usbbridge.nativebridge.CaptureHardwareResult
import com.bitperfect.usbbridge.nativebridge.DacProfile
import com.bitperfect.usbbridge.nativebridge.InspectResult
import com.bitperfect.usbbridge.nativebridge.NativeLogListener
import com.bitperfect.usbbridge.nativebridge.NativeUsbAudioBridge
import com.bitperfect.usbbridge.nativebridge.StreamTelemetry
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
        desc = "ADB 授权 (uid=2000) · 自动嗅探 Apple Music 采样率并无感切频"
    )
}

/**
 * Universal r_submix integer-divisor rate plan protecting both Manual & Shizuku modes
 * against Android RecordThread's non-integer SRC & muffled upsampling:
 *  - 44.1k family (44100 / 88200 / 176400 Hz) -> 44100 Hz (1:1 / 2:1 / 4:1 integer)
 *  - 48.0k family (48000 / 96000 / 192000 Hz) -> 48000 Hz (1:1 / 2:1 / 4:1 integer)
 */
data class EffectiveRatePlan(
    val sourceSampleRate: Int,
    val effectiveSampleRate: Int,
    val integerDivisor: Int,
    val isIntegerDownsampled: Boolean
) {
    fun formatPathSummary(dacFormatTag: String): String {
        val srcKhz = "%.1f kHz".format(sourceSampleRate / 1000.0)
        val effKhz = "%.1f kHz".format(effectiveSampleRate / 1000.0)
        return if (isIntegerDownsampled) {
            "$srcKhz -> $effKhz [${integerDivisor}:1 Int] · $dacFormatTag"
        } else {
            "$srcKhz -> $effKhz [1:1 Zero-SRC] · $dacFormatTag"
        }
    }
}

data class AppleMusicBridgeState(
    val active: Boolean = false,
    val statusMessage: String = "未启动",
    val appleMusicUid: Int = -1,
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
        return plan.formatPathSummary(dacTag)
    }
}

sealed class DacConnectionState {
    data object Disconnected : DacConnectionState()
    data class RequestingPermission(val deviceName: String) : DacConnectionState()

    /**
     * Android USB permission is granted and the DAC is armed, while snd-usb-audio remains untouched
     * until MediaProjection & AudioRecord are initialized (UAPP-style glitch-free sequence).
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
        private const val ACTION_USB_PERMISSION = "com.bitperfect.usbbridge.ACTION_USB_PERMISSION"
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val USB_AUDIO_SUBCLASS_CONTROL = 1
        private const val USB_AUDIO_SUBCLASS_STREAMING = 2
        private const val MAX_LOG_LINES = 650
        private const val VOLUME_DEBOUNCE_MS = 35L

        // Two honest r_submix Zero-SRC / integer-divisor base rates for Manual mode
        val MANUAL_BASE_RATE_OPTIONS = listOf(44100, 48000)

        fun computeEffectiveRatePlan(
            sourceRate: Int,
            profile: DacProfile? = null
        ): EffectiveRatePlan {
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
                isIntegerDownsampled = sourceRate > finalRate
            )
        }
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val reconfigureMutex = Mutex()

    @Volatile
    private var activeSession: ActiveUsbSession? = null

    @Volatile
    private var armedDevice: UsbDevice? = null

    @Volatile
    private var activeMediaProjection: MediaProjection? = null

    @Volatile
    private var lastAppliedVolumeDb: Double = Double.NaN

    @Volatile
    private var remoteSubmixProfile: RemoteSubmixProfile? = null

    // Auto-Heal Watchdog state (detects PAL zeroDropout bursts in both Non-Root & Shizuku modes)
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

    private val deepBufferGuard = DeepBufferKeepAliveGuard { level, msg ->
        appendLog(level, "PAL", msg)
    }

    val shizukuState: StateFlow<ShizukuConnectionState> = shizukuController.state
    val snifferStatus: StateFlow<SnifferStatus> = specSniffer.status

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredUsbAudioDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredUsbAudioDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<DacConnectionState>(DacConnectionState.Disconnected)
    val connectionState: StateFlow<DacConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private val _bridgeState = MutableStateFlow(AppleMusicBridgeState())
    val bridgeState: StateFlow<AppleMusicBridgeState> = _bridgeState.asStateFlow()

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
                    shizukuController.applyBackgroundKeepAlive()
                }
            }
        }

        MediaProjectionBridgeHolder.onProjectionReady = { mp ->
            onMediaProjectionReady(mp)
        }
        MediaProjectionBridgeHolder.onProjectionStopped = {
            appendLog(2, "KT", "系统已结束 MediaProjection 音频捕获会话")
            stopAppleMusicBridge()
        }
        MediaProjectionBridgeHolder.onProjectionError = { err ->
            appendLog(3, "KT", err)
            _bridgeState.update { it.copy(active = false, statusMessage = err) }
        }
    }

    fun start() {
        NativeUsbAudioBridge.setLogListener(nativeLogListener)
        NativeUsbAudioBridge.setIsoThreadListener(null) // Zero-Root architecture
        shizukuController.start()
        refreshSystemMusicVolume()
        appendLog(
            1,
            "KT",
            "BitPerfect USB Bridge v${BuildConfig.VERSION_NAME} (正式版 · 无Root + Shizuku 双模式) 已启动"
        )
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
                    val uid = _bridgeState.value.appleMusicUid
                    if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuController.state.value.isReady && uid > 0) {
                        _bridgeState.update { it.copy(operatingMode = mode) }
                        specSniffer.start(uid) { spec ->
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
            profile = activeSession?.profile
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
     * One-tap clean PAL/USB resync without unplugging (preserves hardware volume dB).
     */
    fun quickResyncBridge() {
        if (!_bridgeState.value.active) return
        scope.launch {
            reconfigureMutex.withLock {
                if (!_bridgeState.value.active) return@withLock
                val curRate = _bridgeState.value.sourceSpec?.sampleRate ?: _manualSampleRate.value
                appendLog(1, "KT", "正在执行无拔插 PAL/USB 无感重同步 (${curRate} Hz)...")
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
            val uid = if (bs.appleMusicUid > 0) bs.appleMusicUid else specSniffer.resolveAppleMusicUid()

            sb.append("========== BitPerfect USB Bridge v${BuildConfig.VERSION_NAME} 诊断快照 ==========\n")
            sb.append("OperatingMode: ${_operatingMode.value.shortLabel} | Shizuku: ${shizukuState.value.shortBadge} | SysMusicVol: ${sysVol.current}/${sysVol.max}\n")
            sb.append("SignalPath: ${bs.formatPathSummary() ?: "未激活"} | status=${bs.statusMessage}\n")
            sb.append("Telemetry: streaming=${tel.streaming} | SPSC=${"%.1f".format(tel.bufferedMs)}/${tel.targetBufferMs}ms (min=${"%.1f".format(tel.minBufferedMs)}ms) | USB(pkts=${tel.packetsSubmitted}, err=${tel.transferErrors}, underrun=${tel.underruns}, maxGap=${"%.1f".format(tel.maxUsbGapMs)}ms) | zeroDrops(runs=${tel.zeroDropoutRuns}, blks=${tel.zeroDropoutBlocks}) | drift(+${tel.driftInsertedFrames}/-${tel.driftDroppedFrames}) | peak=${"%.1f".format(tel.peakDbfs)}dBFS\n")

            if (shizukuController.state.value.isReady) {
                val shizukuDump = shizukuController.dumpDiagnosticsSnapshot(uid)
                if (!shizukuDump.isNullOrBlank()) {
                    sb.append(shizukuDump).append('\n')
                }
            } else {
                sb.append("(提示: 当前未连接 Shizuku，仅导出本应用遥测；授权 Shizuku 后可同步抓取 PAL / AudioFlinger / dumpsys audio 快照)\n")
            }

            val fullText = sb.toString()
            fullText.lineSequence().take(90).forEach { line ->
                if (line.isNotBlank()) appendLog(0, "DIAG", line)
            }
            onReady?.invoke(fullText)
        }
    }

    // -------------------------------------------------------------------------
    // USB Discovery & Connection (UAPP-style Deferred Claim + On-Demand Inspect)
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

        // Auto-arm single already-permitted USB DAC on launch so user doesn't even need an extra tap!
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

    /**
     * Claims USB DAC immediately when user wants to inspect UAC2 descriptors or run 440Hz self-test
     * before starting MediaProjection.
     */
    fun claimArmedUsbNow() {
        val dev = armedDevice ?: return
        scope.launch {
            reconfigureMutex.withLock {
                if (activeSession == null) {
                    deepBufferGuard.ensureStartedAndPreWarmed(260L)
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
        appendLog(
            1,
            "KT",
            "$name ($vidPid) 已就绪待命 —— 点击「启动直通」将自动完成 PAL 预热与两阶段无损接管"
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
            "已打开 UsbDeviceConnection: $name (fd=$fd)，执行 C++ 两阶段纯净接管 (IF#2 -> IF#0，跳过 IF#1 麦克风)..."
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
    // MediaProjection Bridge Lifecycle (Pure Non-Root Manual & Shizuku Auto)
    // -------------------------------------------------------------------------

    fun onMediaProjectionGranted(resultCode: Int, resultData: Intent) {
        if (activeSession == null && armedDevice == null) {
            appendLog(2, "KT", "请先接入并授权 USB DAC 后再启动直通")
            return
        }
        appendLog(1, "KT", "已获得 MediaProjection 授权，正在启动前台音频直通服务...")
        val prepText = if (AppLanguageManager.isChinese(appContext)) {
            "Apple Music USB 独占直通准备中..."
        } else {
            "Preparing Apple Music USB exclusive bridge..."
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

                val uid = specSniffer.resolveAppleMusicUid()
                if (uid <= 0) {
                    appendLog(2, "KT", "未检测到 com.apple.android.music，将按通用媒体捕获模式运行")
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

                var initialSourceRate = _manualSampleRate.value
                var initialSourceTag = "manual:${initialSourceRate}Hz"
                if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuReady) {
                    shizukuController.applyBackgroundKeepAlive()
                    if (remoteSubmixProfile == null) {
                        remoteSubmixProfile = specSniffer.probeRemoteSubmixProfile()
                    }
                    if (uid > 0) {
                        val quickDump = shizukuController.dumpAudioPlayback(uid)
                        if (!quickDump.isNullOrBlank()) {
                            val quickRate = extractStartedRateFromDump(quickDump, uid)
                            if (quickRate != null) {
                                initialSourceRate = quickRate
                                initialSourceTag = "shizuku:initial:${quickRate}Hz"
                                appendLog(1, "SNIFF", "Shizuku 初始检测到 Apple Music 采样率: $quickRate Hz")
                            }
                        }
                    }
                }

                // Pre-warm Qualcomm PAL_STREAM_DEEP_BUFFER BEFORE detaching snd-usb-audio
                deepBufferGuard.ensureStartedAndPreWarmed(
                    preWarmMs = if (activeSession == null) 260L else 0L
                )

                val initialPlan = computeEffectiveRatePlan(
                    sourceRate = initialSourceRate,
                    profile = activeSession?.profile
                )

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

                    val capRes = capturePipeline.startCapture(
                        mediaProjection = mediaProjection,
                        targetUid = uid,
                        sampleRate = initialPlan.effectiveSampleRate,
                        onFatalError = { err ->
                            appendLog(3, "CAP", "音频捕获中止: $err")
                            _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
                        }
                    )
                    val activeCap = capRes.getOrElse { err ->
                        val msg = "启动 AudioRecord 失败: ${err.message}"
                        appendLog(3, "KT", msg)
                        _bridgeState.value = AppleMusicBridgeState(
                            active = false,
                            statusMessage = msg,
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
                        _bridgeState.value = AppleMusicBridgeState(
                            active = false,
                            statusMessage = "接管 USB DAC 失败",
                            operatingMode = mode
                        )
                        return@withLock
                    }

                    val finalPlan = computeEffectiveRatePlan(
                        sourceRate = initialSourceRate,
                        profile = session.profile
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
                        NativeUsbAudioBridge.close()
                        runCatching { session.connection.close() }
                        activeSession = null
                        _telemetry.value = StreamTelemetry()
                        val name = deviceDisplayName(targetDevice)
                        val vidPid = "%04X:%04X".format(targetDevice.vendorId, targetDevice.productId)
                        _connectionState.value = DacConnectionState.ArmedStandby(targetDevice, name, vidPid)
                        _bridgeState.value = AppleMusicBridgeState(
                            active = false,
                            statusMessage = msg,
                            operatingMode = mode
                        )
                        return@withLock
                    }

                    val finalCap = if (hw.dacSampleRate != activeCap.sampleRate) {
                        capturePipeline.stopCapture()
                        capturePipeline.startCapture(
                            mediaProjection = mediaProjection,
                            targetUid = uid,
                            sampleRate = hw.dacSampleRate,
                            onFatalError = { err ->
                                appendLog(3, "CAP", "音频捕获中止: $err")
                                _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
                            }
                        ).getOrNull() ?: activeCap
                    } else {
                        activeCap
                    }

                    // Re-check system volume after snd-usb-audio detachment (Android switches STREAM_MUSIC to Speaker index!)
                    refreshSystemMusicVolume()

                    val nextState = AppleMusicBridgeState(
                        active = true,
                        statusMessage = if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuReady) {
                            "Shizuku 自动直通中 · 可切回 Apple Music 播放"
                        } else {
                            "无 Root 独占直通中 · 可切回 Apple Music 播放"
                        },
                        appleMusicUid = uid,
                        operatingMode = mode,
                        sourceSpec = DetectedAudioSpec(initialSourceRate, 24, initialSourceTag),
                        ratePlan = finalPlan,
                        dacHardware = hw,
                        captureConfig = finalCap,
                        reconfigureCount = 1
                    )
                    _bridgeState.value = nextState
                    resetAutoHealWindow()
                    nextState.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
                } else {
                    // If USB was already claimed (e.g. user ran 440Hz self-test first), run clean PAL rebind start
                    _bridgeState.value = AppleMusicBridgeState(
                        active = true,
                        appleMusicUid = uid,
                        operatingMode = mode,
                        statusMessage = "正在锁定 DAC 与 MediaProjection..."
                    )
                    reconfigureCaptureAndHardwareLocked(
                        sourceSampleRate = initialSourceRate,
                        sourceTag = initialSourceTag,
                        forceRebind = true
                    )
                }

                if (mode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuReady && uid > 0) {
                    specSniffer.start(uid) { spec ->
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

                val newPlan = computeEffectiveRatePlan(
                    sourceRate = spec.sampleRate,
                    profile = session.profile
                )
                val curSpec = _bridgeState.value.sourceSpec
                val curPlan = _bridgeState.value.ratePlan
                val curHw = _bridgeState.value.dacHardware

                // Proactive PAL rebind whenever Apple Music starts a new Hi-Res track (> 48kHz)
                val isNewHiResTrack = spec.sampleRate > 48000 &&
                    (spec.piid != curSpec?.piid || spec.sampleRate != curSpec?.sampleRate)

                if (!isNewHiResTrack &&
                    curPlan != null && curHw != null &&
                    curPlan.effectiveSampleRate == newPlan.effectiveSampleRate &&
                    capturePipeline.isRunning
                ) {
                    val updated = _bridgeState.value.copy(
                        sourceSpec = spec,
                        ratePlan = newPlan,
                        statusMessage = "Shizuku 自动直通中 · 同族无缝锁定 ${newPlan.effectiveSampleRate / 1000.0} kHz"
                    )
                    _bridgeState.value = updated
                    updated.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
                    appendLog(
                        1,
                        "SNIFF",
                        "同族采样率切换 (${spec.sampleRate} Hz -> ${newPlan.effectiveSampleRate} Hz)，保持零中断输出"
                    )
                    return@withLock
                }

                if (isNewHiResTrack) {
                    appendLog(
                        1,
                        "SNIFF",
                        "检测到 Hi-Res 新音轨 (${spec.sampleRate} Hz, piid=${spec.piid}) -> 主动执行 PAL 无卡顿重同步"
                    )
                }

                reconfigureCaptureAndHardwareLocked(
                    sourceSampleRate = spec.sampleRate,
                    sourceTag = spec.sourceTag,
                    forceRebind = isNewHiResTrack,
                    trackPiid = spec.piid
                )
            }
        }
    }

    private suspend fun reconfigureCaptureAndHardwareLocked(
        sourceSampleRate: Int,
        sourceTag: String,
        forceRebind: Boolean = false,
        trackPiid: Int = 0
    ) {
        val curSession = activeSession
        val targetDevice = curSession?.device ?: armedDevice ?: return
        val mp = activeMediaProjection ?: return
        val uid = _bridgeState.value.appleMusicUid
        val preferredBits = _preferredBridgeBitDepth.value

        val plan = computeEffectiveRatePlan(
            sourceRate = sourceSampleRate,
            profile = curSession?.profile
        )

        val curCap = _bridgeState.value.captureConfig
        val curHw = _bridgeState.value.dacHardware
        val captureUnchanged = curCap != null &&
            capturePipeline.isRunning &&
            curCap.sampleRate == plan.effectiveSampleRate

        // Case 1: Same effective rate & bit-depth
        if (!forceRebind && curSession != null && captureUnchanged && curHw != null && curHw.dacBitResolution == preferredBits) {
            val updated = _bridgeState.value.copy(
                active = true,
                operatingMode = _operatingMode.value,
                sourceSpec = DetectedAudioSpec(sourceSampleRate, 24, sourceTag, piid = trackPiid),
                ratePlan = plan.copy(effectiveSampleRate = curHw.dacSampleRate),
                statusMessage = if (_operatingMode.value == BridgeOperatingMode.SHIZUKU_AUTO) {
                    "Shizuku 自动直通中 · 同族无缝锁定 ${curHw.dacSampleRate / 1000.0} kHz"
                } else {
                    "无 Root 独占直通中 · 已锁定 ${curHw.dacSampleRate / 1000.0} kHz"
                }
            )
            _bridgeState.value = updated
            updated.formatPathSummary()?.let { UsbBridgeService.updateStatus(appContext, it) }
            return
        }

        // Case 2: Only DAC bit-depth changed (24-bit <-> 32-bit) at same rate -> keep AudioRecord alive!
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

        // Case 3: Cross-family rate switch (44.1k <-> 48k), Hi-Res rebind, or Auto-Heal
        val savedVolumeDb = if (curSession != null) _volumeDb.value else 0.0
        appendLog(
            1,
            "KT",
            "执行无卡顿切频重同步 [${_operatingMode.value.shortLabel}]: " +
                "${plan.formatPathSummary("S${preferredBits}")} | 保持硬件音量=${"%.1f".format(savedVolumeDb)}dB"
        )

        capturePipeline.stopCapture()

        if (curSession != null) {
            deepBufferGuard.stop()
            telemetryJob?.cancel()
            telemetryJob = null
            NativeUsbAudioBridge.close(restoreUnityVolume = false, resetUsbPort = false)
            runCatching { curSession.connection.close() }
            activeSession = null
            armedDevice = targetDevice
            delay(320L)
        }

        deepBufferGuard.ensureStartedAndPreWarmed(260L)

        val capRes = capturePipeline.startCapture(
            mediaProjection = mp,
            targetUid = uid,
            sampleRate = plan.effectiveSampleRate,
            onFatalError = { err ->
                appendLog(3, "CAP", "音频捕获中止: $err")
                _bridgeState.update { it.copy(statusMessage = "捕获异常: $err") }
            }
        )
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
            profile = newSession.profile
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
            operatingMode = _operatingMode.value,
            sourceSpec = DetectedAudioSpec(sourceSampleRate, 24, sourceTag, piid = trackPiid),
            ratePlan = finalPlan.copy(effectiveSampleRate = hw.dacSampleRate),
            dacHardware = hw,
            captureConfig = activeCap,
            reconfigureCount = prevCount + 1,
            statusMessage = if (_operatingMode.value == BridgeOperatingMode.SHIZUKU_AUTO) {
                "Shizuku 自动直通中 · 可切回 Apple Music 播放"
            } else {
                "无 Root 独占直通中 · 可切回 Apple Music 播放"
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

    fun stopAppleMusicBridge() {
        scope.launch {
            reconfigureMutex.withLock {
                stopAppleMusicBridgeLocked(releaseUsbToStandby = true)
            }
        }
    }

    private suspend fun stopAppleMusicBridgeLocked(releaseUsbToStandby: Boolean) {
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
                deepBufferGuard.stop()
                val dev = session.device
                telemetryJob?.cancel()
                telemetryJob = null
                NativeUsbAudioBridge.close(restoreUnityVolume = true, resetUsbPort = false)
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
            deepBufferGuard.stop()
            UsbBridgeService.stop(appContext)
        }

        _bridgeState.value = AppleMusicBridgeState(
            active = false,
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
                    deepBufferGuard.ensureStartedAndPreWarmed(260L)
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
                        stopAppleMusicBridgeLocked(releaseUsbToStandby = false)
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
                    deepBufferGuard.ensureStartedAndPreWarmed(260L)
                    if (openUsbSessionLocked(dev) == null) return@withLock
                }
                if (_bridgeState.value.active) {
                    stopAppleMusicBridgeLocked(releaseUsbToStandby = false)
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
    // Hardware Volume & Ring Buffer Controls
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
        val clamped = ms.coerceIn(100, 600)
        _bufferDurationMs.value = clamped
        NativeUsbAudioBridge.setBufferDurationMs(clamped)
        updateTelemetryOnce()
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
            deepBufferGuard.stop()
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
            remoteSubmixProfile = null

            if (session != null) {
                appendLog(1, "KT", "正在释放 USB DAC ($reason): Alt#0 -> 0.00 dB -> 归还 snd-usb-audio...")
                NativeUsbAudioBridge.close(restoreUnityVolume = true, resetUsbPort = false)
                runCatching { session.connection.close() }
                delay(280L)
            }

            UsbBridgeService.stop(appContext)
            _telemetry.value = StreamTelemetry()
            _bridgeState.value = AppleMusicBridgeState(operatingMode = _operatingMode.value)
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

        // Automatic Zero-Drop Self-Healing Watchdog (works in BOTH Pure Non-Root & Shizuku modes)
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
                "检测到连续静音洞卡顿 (3.5s 内 ΔzeroDrops=$deltaEvents)，自动执行 PAL/USB 无卡顿重绑定自愈..."
            )
            scope.launch {
                reconfigureMutex.withLock {
                    if (!_bridgeState.value.active) return@withLock
                    val curRate = _bridgeState.value.sourceSpec?.sampleRate ?: _manualSampleRate.value
                    reconfigureCaptureAndHardwareLocked(
                        sourceSampleRate = curRate,
                        sourceTag = "auto-heal",
                        forceRebind = true,
                        trackPiid = _bridgeState.value.sourceSpec?.piid ?: 0
                    )
                }
            }
        }
    }

    private fun extractStartedRateFromDump(output: String, uid: Int): Int? {
        val uidNeedle = "u/pid:$uid/"
        val rateRegex = Regex("""FormatInfo\{[^}]*sampleRate=(\d+)""")
        val validRates = setOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)
        for (line in output.lineSequence()) {
            if (!line.contains(uidNeedle) || !line.contains("state:started")) continue
            val hz = rateRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (hz != null && hz in validRates) return hz
        }
        return null
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
