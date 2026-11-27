package com.bitperfect.rootdac.usb

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
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitperfect.rootdac.BuildConfig
import com.bitperfect.rootdac.capture.ActiveCaptureConfig
import com.bitperfect.rootdac.capture.LspConnectionStatus
import com.bitperfect.rootdac.capture.LspDirectServer
import com.bitperfect.rootdac.capture.LspSourceFormatEvent
import com.bitperfect.rootdac.capture.RootLoopbackPipeline
import com.bitperfect.rootdac.i18n.AppLanguageManager
import com.bitperfect.rootdac.nativebridge.CaptureHardwareResult
import com.bitperfect.rootdac.nativebridge.DacProfile
import com.bitperfect.rootdac.nativebridge.InspectResult
import com.bitperfect.rootdac.nativebridge.NativeIsoThreadListener
import com.bitperfect.rootdac.nativebridge.NativeLogListener
import com.bitperfect.rootdac.nativebridge.NativeUsbAudioBridge
import com.bitperfect.rootdac.nativebridge.StreamTelemetry
import com.bitperfect.rootdac.root.AppleMusicSpecSniffer
import com.bitperfect.rootdac.root.DetectedAudioSpec
import com.bitperfect.rootdac.root.RemoteSubmixProfile
import com.bitperfect.rootdac.root.SnifferStatus
import com.bitperfect.rootdac.service.RootDacService
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

enum class RootCaptureEngineMode {
    /** LSPosed in-process AudioTrack hook -> True 16/24/32-bit 44.1k..192kHz Bit-Perfect (ZERO r_submix cap) */
    LSPOSED_DIRECT,

    /** Root AudioPolicy (ROUTE_FLAG_LOOP_BACK) fallback -> No LSPosed required, capped by ROM r_submix (48kHz) */
    ROOT_SUBMIX_LOOPBACK
}

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

data class RootLoopbackBridgeState(
    val active: Boolean = false,
    val engineMode: RootCaptureEngineMode = RootCaptureEngineMode.LSPOSED_DIRECT,
    val statusMessage: String = "待机",
    val appleMusicUid: Int = -1,
    val sourceSpec: DetectedAudioSpec? = null,
    val sourceEncodingTag: String = "",
    val dacHardware: CaptureHardwareResult? = null,
    val captureConfig: ActiveCaptureConfig? = null,
    val isBitPerfectRateMatch: Boolean = true,
    val submixRateCapped: Boolean = false,
    val reconfigureCount: Int = 0
) {
    fun formatPathSummary(): String? {
        val src = sourceSpec ?: return null
        val dac = dacHardware ?: return null
        val srcKhz = "%.1f kHz".format(src.sampleRate / 1000.0)
        val dacKhz = "%.1f kHz".format(dac.dacSampleRate / 1000.0)
        val encSuffix = if (sourceEncodingTag.isNotBlank()) " ($sourceEncodingTag)" else ""
        return when {
            engineMode == RootCaptureEngineMode.LSPOSED_DIRECT && isBitPerfectRateMatch ->
                "Source: ${src.bitDepth}-bit / $srcKhz$encSuffix → DAC: ${dac.shortFormatTag} / $dacKhz [1:1 Bit-Perfect]"
            engineMode == RootCaptureEngineMode.LSPOSED_DIRECT ->
                "Source: ${src.bitDepth}-bit / $srcKhz$encSuffix → DAC: ${dac.shortFormatTag} / $dacKhz [LSPosed 直截]"
            submixRateCapped ->
                "Source: ${src.bitDepth}-bit / $srcKhz → r_submix $dacKhz → DAC: ${dac.shortFormatTag} / $dacKhz (48k 上限)"
            isBitPerfectRateMatch ->
                "Source: ${src.bitDepth}-bit / $srcKhz → DAC: ${dac.shortFormatTag} / $dacKhz (Zero-SRC)"
            else ->
                "Source: ${src.bitDepth}-bit / $srcKhz → DAC: ${dac.shortFormatTag} / $dacKhz (Rate-Mapped)"
        }
    }
}

sealed class DacConnectionState {
    data object Disconnected : DacConnectionState()
    data class RequestingPermission(val deviceName: String) : DacConnectionState()
    data class Connecting(val deviceName: String) : DacConnectionState()
    data class Connected(
        val device: UsbDevice,
        val profile: DacProfile,
        val fullReport: String
    ) : DacConnectionState()
    data class Error(val message: String) : DacConnectionState()
}

private data class ActiveUsbSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val profile: DacProfile
)

@OptIn(FlowPreview::class)
class RootDacManager(context: Context) {

    companion object {
        private const val TAG = "RootDacManager"
        private const val ACTION_USB_PERMISSION = "com.bitperfect.rootdac.ACTION_USB_PERMISSION"
        private const val USB_AUDIO_SUBCLASS_CONTROL = 1
        private const val USB_AUDIO_SUBCLASS_STREAMING = 2
        private const val MAX_LOG_LINES = 600
        private const val VOLUME_DEBOUNCE_MS = 35L
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val reconfigureMutex = Mutex()

    @Volatile
    private var activeSession: ActiveUsbSession? = null
    @Volatile
    private var lastAppliedVolumeDb: Double = Double.NaN
    @Volatile
    private var remoteSubmixProfile: RemoteSubmixProfile? = null
    @Volatile
    private var pendingAutoStartBridge = false
    private var receiversRegistered = false
    private var telemetryJob: Job? = null

    private val volumeInputFlow = MutableSharedFlow<Double>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val specSniffer = AppleMusicSpecSniffer(appContext) { level, msg ->
        appendLog(level, "ROOT", msg)
    }

    private val rootLoopbackPipeline = RootLoopbackPipeline(appContext) { level, msg ->
        appendLog(level, "CAP", msg)
    }

    private val lspDirectServer = LspDirectServer { level, msg ->
        appendLog(level, "LSP", msg)
    }

    val snifferStatus: StateFlow<SnifferStatus> = specSniffer.status
    val lspStatus: StateFlow<LspConnectionStatus> = lspDirectServer.status

    private val _engineMode = MutableStateFlow(RootCaptureEngineMode.LSPOSED_DIRECT)
    val engineMode: StateFlow<RootCaptureEngineMode> = _engineMode.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredUsbAudioDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredUsbAudioDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<DacConnectionState>(DacConnectionState.Disconnected)
    val connectionState: StateFlow<DacConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private val _bridgeState = MutableStateFlow(RootLoopbackBridgeState())
    val bridgeState: StateFlow<RootLoopbackBridgeState> = _bridgeState.asStateFlow()

    private val _selectedPreset = MutableStateFlow(SineTestPreset(192000, 24, "192 kHz / 24-bit"))
    val selectedPreset: StateFlow<SineTestPreset> = _selectedPreset.asStateFlow()

    private val _volumeDb = MutableStateFlow(0.0)
    val volumeDb: StateFlow<Double> = _volumeDb.asStateFlow()

    private val _bufferDurationMs = MutableStateFlow(300)
    val bufferDurationMs: StateFlow<Int> = _bufferDurationMs.asStateFlow()

    /** 0 = Auto match source bit-depth (16/24/32-bit), 24 = S24_3LE (Alt#2), 32 = S32_LE (Alt#3) */
    private val _preferredBridgeBitDepth = MutableStateFlow(0)
    val preferredBridgeBitDepth: StateFlow<Int> = _preferredBridgeBitDepth.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val nativeLogListener = object : NativeLogListener {
        override fun onNativeLog(level: Int, message: String) {
            appendLog(level = level, source = "C++", message = message)
        }
    }

    private val nativeIsoThreadListener = object : NativeIsoThreadListener {
        override fun onIsoThreadStarted(tid: Int) {
            specSniffer.elevateThreadToSchedFifo(
                tid = tid,
                priority = 50,
                label = "USB_ISO",
                isIsoThread = true
            )
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            refreshDevices()
            if (device == null) {
                appendLog(3, "KT", "USB 授权回调缺少 UsbDevice 参数")
                _connectionState.value = DacConnectionState.Error("USB permission callback missing device")
                return
            }
            if (granted) {
                val autoStart = pendingAutoStartBridge
                pendingAutoStartBridge = false
                appendLog(1, "KT", "USB 权限已授予: ${deviceDisplayName(device)}")
                connectWithPermission(device, autoStartBridgeAfterConnect = autoStart)
            } else {
                pendingAutoStartBridge = false
                appendLog(2, "KT", "USB 权限被拒绝: ${deviceDisplayName(device)}")
                _connectionState.value = DacConnectionState.Error("用户拒绝了 USB 授权")
            }
        }
    }

    private val hotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    appendLog(1, "KT", "检测到 USB 设备接入: ${dev?.let(::deviceDisplayName) ?: "unknown"}")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    appendLog(2, "KT", "检测到 USB 设备拔出: ${dev?.let(::deviceDisplayName) ?: "unknown"}")
                    if (dev != null && activeSession?.device?.deviceId == dev.deviceId) {
                        disconnect("USB DAC 已物理拔出")
                    }
                    refreshDevices()
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
    }

    fun start() {
        NativeUsbAudioBridge.setLogListener(nativeLogListener)
        NativeUsbAudioBridge.setIsoThreadListener(nativeIsoThreadListener)
        appendLog(
            1,
            "KT",
            "BitPerfect Root DAC v${BuildConfig.VERSION_NAME} 已启动"
        )
        if (!receiversRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                permissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val hotplugFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                appContext,
                hotplugReceiver,
                hotplugFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiversRegistered = true
        }
        refreshDevices()
    }

    fun stop() {
        runBlocking {
            withTimeoutOrNull(1500L) {
                disconnectInternal("Manager stopped")
            }
        }
        if (receiversRegistered) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            runCatching { appContext.unregisterReceiver(hotplugReceiver) }
            receiversRegistered = false
        }
        NativeUsbAudioBridge.setIsoThreadListener(null)
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

    fun dumpSnifferDiagnosticSnapshot(onReady: ((String) -> Unit)? = null) {
        val t = NativeUsbAudioBridge.getTelemetry()
        val diagLine = "[DIAG] engine=${_engineMode.value.name}, streaming=${t.streaming}, rate=${t.sampleRate}Hz, fmt=${t.format}, " +
            "SPSC=${"%.1f".format(t.bufferedMs)}/${t.targetBufferMs}ms (min=${"%.1f".format(t.minBufferedMs)}ms), " +
            "underruns=${t.underruns}, xferErr=${t.transferErrors}, maxGap=${"%.2f".format(t.maxUsbGapMs)}ms, " +
            "drift=(+${t.driftInsertedFrames}/-${t.driftDroppedFrames}), peak=${"%.1f".format(t.peakDbfs)}dBFS"
        appendLog(1, "C++", diagLine)
        specSniffer.dumpDiagnosticSnapshot(headerLine = diagLine, onReady = onReady)
    }

    fun setEngineMode(mode: RootCaptureEngineMode) {
        if (_engineMode.value == mode) return
        _engineMode.value = mode
        val modeLabel = when (mode) {
            RootCaptureEngineMode.LSPOSED_DIRECT -> "LSPosed 源码直截 (真 16/24/32-bit · 192kHz)"
            RootCaptureEngineMode.ROOT_SUBMIX_LOOPBACK -> "Root 环回备选 (免 LSPosed · 限 48kHz)"
        }
        appendLog(1, "KT", "已切换捕获引擎 -> $modeLabel")
        if (_bridgeState.value.active) {
            scope.launch {
                stopRootLoopbackBridgeInternal(updateServiceNotification = false)
                startRootLoopbackBridge()
            }
        } else {
            _bridgeState.update { it.copy(engineMode = mode) }
        }
    }

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
    }

    fun requestConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            appendLog(1, "KT", "已有 USB 权限: ${deviceDisplayName(device)}")
            connectWithPermission(device)
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

    // -------------------------------------------------------------------------
    // Dual-Engine Bridge Lifecycle: LSPosed 192kHz Direct & Root Loopback
    // -------------------------------------------------------------------------

    fun startRootLoopbackBridge() {
        if (activeSession == null) {
            val firstDev = _discoveredDevices.value.firstOrNull()
            if (firstDev == null) {
                appendLog(2, "KT", "未检测到外接 USB DAC，请插入小尾巴后再启动直通")
                return
            }
            if (usbManager.hasPermission(firstDev.device)) {
                appendLog(1, "KT", "一键启动：先独占接管 ${firstDev.displayName} 再激活源码直通...")
                connectWithPermission(firstDev.device, autoStartBridgeAfterConnect = true)
            } else {
                pendingAutoStartBridge = true
                requestConnect(firstDev.device)
            }
            return
        }
        scope.launch {
            startBridgeWithActiveSessionInternal()
        }
    }

    private suspend fun startBridgeWithActiveSessionInternal() {
        reconfigureMutex.withLock {
            if (activeSession == null) return@withLock
            NativeUsbAudioBridge.stopStream()
            lspDirectServer.stop()
            rootLoopbackPipeline.stopCapture()
            specSniffer.stop()

            val uid = specSniffer.resolveAppleMusicUid()
            if (uid <= 0) {
                val msg = "未检测到 Apple Music，请先安装"
                appendLog(3, "KT", msg)
                _bridgeState.value = RootLoopbackBridgeState(
                    active = false,
                    engineMode = _engineMode.value,
                    statusMessage = msg
                )
                return@withLock
            }

            specSniffer.applyRootBackgroundKeepAlive()

            val mode = _engineMode.value
            if (mode == RootCaptureEngineMode.LSPOSED_DIRECT) {
                NativeUsbAudioBridge.setDirectBurstMode(true)
                val srvRes = lspDirectServer.start(
                    onPrepareHardwareForFormat = { evt ->
                        reconfigureLspDirectForEvent(evt)
                    },
                    onFlushRequested = { _ ->
                        NativeUsbAudioBridge.flushCaptureBuffer()
                    }
                )
                if (srvRes.isFailure) {
                    val err = "本地端口绑定失败: ${srvRes.exceptionOrNull()?.message}"
                    appendLog(3, "LSP", err)
                    _bridgeState.value = RootLoopbackBridgeState(
                        active = false,
                        engineMode = mode,
                        statusMessage = err
                    )
                    return@withLock
                }

                _bridgeState.value = RootLoopbackBridgeState(
                    active = true,
                    engineMode = mode,
                    appleMusicUid = uid,
                    statusMessage = "源码直通已就绪 · 打开 Apple Music 播放即可"
                )
                val waitText = if (AppLanguageManager.isChinese(appContext)) {
                    "LSPosed 原生源码直通 · 等待播放"
                } else {
                    "LSPosed Bit-Perfect Direct · Waiting for playback"
                }
                RootDacService.updateStatus(appContext, waitText)
            } else {
                NativeUsbAudioBridge.setDirectBurstMode(false)
                if (remoteSubmixProfile == null) {
                    remoteSubmixProfile = specSniffer.probeRemoteSubmixProfile()
                }

                _bridgeState.value = RootLoopbackBridgeState(
                    active = true,
                    engineMode = mode,
                    appleMusicUid = uid,
                    statusMessage = "Root 环回备选监听中 (限 48kHz)"
                )
                val waitLoop = if (AppLanguageManager.isChinese(appContext)) {
                    "Root 环回备选 · 等待播放"
                } else {
                    "Root Loopback · Waiting for playback"
                }
                RootDacService.updateStatus(appContext, waitLoop)
                specSniffer.start { detectedSpec ->
                    scope.launch {
                        reconfigureRootSubmixForSpec(detectedSpec)
                    }
                }
            }
        }
    }

    private suspend fun reconfigureLspDirectForEvent(evt: LspSourceFormatEvent): Boolean {
        return reconfigureMutex.withLock {
            val session = activeSession ?: return@withLock false
            val state = _bridgeState.value
            if (!state.active || state.engineMode != RootCaptureEngineMode.LSPOSED_DIRECT) {
                return@withLock false
            }

            val (dacTargetRate, dacExactMatch) = session.profile.resolveDacSampleRate(evt.sampleRate)
            val userPrefBits = _preferredBridgeBitDepth.value
            val desiredBits = if (userPrefBits == 0) {
                evt.sourceBitDepth.coerceIn(16, 32)
            } else {
                userPrefBits
            }

            appendLog(
                1,
                "LSP",
                "=== 硬件锁频: Apple Music ${evt.sourceBitDepth}-bit / ${evt.sampleRate} Hz (${evt.encodingName}) -> DAC ${dacTargetRate} Hz (${desiredBits}-bit) ==="
            )

            NativeUsbAudioBridge.setDirectBurstMode(true)
            val prepRes = NativeUsbAudioBridge.prepareCaptureStream(
                sampleRate = dacTargetRate,
                preferredBitDepth = desiredBits
            )
            val hwCfg = prepRes.getOrElse { err ->
                val msg = "DAC 硬件时钟切换失败: ${err.message}"
                appendLog(3, "LSP", msg)
                _bridgeState.update { it.copy(statusMessage = msg) }
                return@withLock false
            }

            lastAppliedVolumeDb = Double.NaN
            applyHardwareVolumeDb(_volumeDb.value)

            val srcSpec = DetectedAudioSpec(
                sampleRate = evt.sampleRate,
                bitDepth = evt.sourceBitDepth,
                rawFormatName = evt.encodingName,
                detectionSource = "LSPosed AudioTrack",
                piid = evt.trackId.toLong()
            )

            val updated = _bridgeState.value.copy(
                active = true,
                engineMode = RootCaptureEngineMode.LSPOSED_DIRECT,
                sourceSpec = srcSpec,
                sourceEncodingTag = evt.encodingName,
                dacHardware = hwCfg,
                captureConfig = null,
                isBitPerfectRateMatch = dacExactMatch,
                submixRateCapped = false,
                reconfigureCount = _bridgeState.value.reconfigureCount + 1,
                statusMessage = "直通输出中 · ${evt.sourceBitDepth}-bit / ${"%.1f".format(evt.sampleRate / 1000.0)} kHz"
            )
            _bridgeState.value = updated

            val summary = updated.formatPathSummary() ?: "LSPosed 源码直通运行中"
            appendLog(1, "LSP", "✓ 锁频完成: $summary (Alt#${hwCfg.altSetting}, 音量=${"%.1f".format(_volumeDb.value)} dB)")
            RootDacService.updateStatus(appContext, summary)
            _telemetry.value = NativeUsbAudioBridge.getTelemetry()
            true
        }
    }

    private suspend fun reconfigureRootSubmixForSpec(sourceSpec: DetectedAudioSpec) {
        reconfigureMutex.withLock {
            val session = activeSession ?: return
            val state = _bridgeState.value
            val uid = state.appleMusicUid
            if (!state.active || state.engineMode != RootCaptureEngineMode.ROOT_SUBMIX_LOOPBACK || uid <= 0) return

            val submixProfile = remoteSubmixProfile ?: specSniffer.probeRemoteSubmixProfile().also {
                remoteSubmixProfile = it
            }
            val (captureRate, submixNativeMatch) = submixProfile.resolveCaptureSampleRate(sourceSpec.sampleRate)
            val submixRateCapped = !submixNativeMatch
            val (dacTargetRate, dacExactMatch) = session.profile.resolveDacSampleRate(captureRate)
            val exactRateMatch = submixNativeMatch && dacExactMatch

            NativeUsbAudioBridge.stopStream()
            rootLoopbackPipeline.stopCapture()
            NativeUsbAudioBridge.setDirectBurstMode(false)

            val prefBits = _preferredBridgeBitDepth.value
            val targetBits = if (prefBits == 0) 24 else prefBits
            val prepRes = NativeUsbAudioBridge.prepareCaptureStream(
                sampleRate = dacTargetRate,
                preferredBitDepth = targetBits
            )
            val hwCfg = prepRes.getOrElse { err ->
                val msg = "DAC 硬件时钟切换失败: ${err.message}"
                appendLog(3, "KT", msg)
                _bridgeState.update { it.copy(statusMessage = msg) }
                return
            }

            lastAppliedVolumeDb = Double.NaN
            applyHardwareVolumeDb(_volumeDb.value)

            val capRes = rootLoopbackPipeline.startCapture(
                targetUid = uid,
                sampleRate = hwCfg.dacSampleRate,
                onCaptureThreadStarted = { capTid ->
                    appendLog(1, "KT", "RootLoopback 捕获线程已启动 (tid=$capTid)")
                },
                onFatalError = { errMsg ->
                    scope.launch {
                        appendLog(3, "KT", "Root 环回中止: $errMsg")
                        stopRootLoopbackBridge()
                    }
                }
            )
            val capCfg = capRes.getOrElse { err ->
                val msg = "Root 环回启动失败: ${err.message}"
                appendLog(3, "KT", msg)
                NativeUsbAudioBridge.stopStream()
                _bridgeState.update { it.copy(statusMessage = msg) }
                return
            }

            val updated = _bridgeState.value.copy(
                active = true,
                engineMode = RootCaptureEngineMode.ROOT_SUBMIX_LOOPBACK,
                sourceSpec = sourceSpec,
                sourceEncodingTag = capCfg.encodingName,
                dacHardware = hwCfg,
                captureConfig = capCfg,
                isBitPerfectRateMatch = exactRateMatch,
                submixRateCapped = submixRateCapped,
                reconfigureCount = _bridgeState.value.reconfigureCount + 1,
                statusMessage = "Root 环回运行中 · ${capCfg.sampleRate} Hz"
            )
            _bridgeState.value = updated

            val summary = updated.formatPathSummary() ?: "Apple Music Root 环回中"
            RootDacService.updateStatus(appContext, summary)
            _telemetry.value = NativeUsbAudioBridge.getTelemetry()
        }
    }

    fun stopRootLoopbackBridge() {
        scope.launch {
            stopRootLoopbackBridgeInternal(updateServiceNotification = true)
        }
    }

    private suspend fun stopRootLoopbackBridgeInternal(updateServiceNotification: Boolean) {
        reconfigureMutex.withLock {
            val wasActive = _bridgeState.value.active ||
                lspDirectServer.isRunning ||
                rootLoopbackPipeline.isRunning

            lspDirectServer.stop()
            specSniffer.stop()
            rootLoopbackPipeline.stopCapture()

            if (wasActive) {
                NativeUsbAudioBridge.stopStream()
                specSniffer.clearIsoSchedStatus()
            }

            _bridgeState.value = RootLoopbackBridgeState(
                active = false,
                engineMode = _engineMode.value,
                statusMessage = "待机"
            )
            _telemetry.value = NativeUsbAudioBridge.getTelemetry()

            if (updateServiceNotification && wasActive) {
                val session = activeSession
                if (session != null) {
                    RootDacService.updateStatus(
                        appContext,
                        "${session.profile.productName} (${session.profile.uacVersion}) · 待机"
                    )
                }
            }
            if (wasActive) {
                appendLog(1, "KT", "已停止直通音频流，DAC 归位 Alt#0 待机态")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Built-in 440Hz Reference Sine Wave Self-Test
    // -------------------------------------------------------------------------

    fun toggleSineTest() {
        if (_telemetry.value.sineRunning) {
            stopAudioStream()
        } else {
            val preset = _selectedPreset.value
            startSineTest(preset.sampleRate, preset.bitDepth)
        }
    }

    fun switchSinePreset(preset: SineTestPreset) {
        _selectedPreset.value = preset
        startSineTest(preset.sampleRate, preset.bitDepth)
    }

    fun startSineTest(sampleRate: Int, bitDepth: Int) {
        scope.launch {
            if (activeSession == null) {
                appendLog(2, "KT", "未连接 USB DAC，无法启动 440Hz 测试音")
                return@launch
            }
            if (_bridgeState.value.active) {
                stopRootLoopbackBridgeInternal(updateServiceNotification = true)
            }
            applyHardwareVolumeDb(_volumeDb.value)
            appendLog(1, "KT", "启动 440Hz 测试音 @ $sampleRate Hz / $bitDepth-bit...")
            val result = NativeUsbAudioBridge.startSineTest(sampleRate, bitDepth)
            if (result.isSuccess) {
                val khz = if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz" else "${"%.1f".format(sampleRate / 1000.0)} kHz"
                RootDacService.updateStatus(appContext, "440Hz 测试音 · $bitDepth-bit / $khz · Bit-Perfect")
            } else {
                appendLog(3, "KT", "startSineTest failed: ${result.exceptionOrNull()?.message}")
            }
            _telemetry.value = NativeUsbAudioBridge.getTelemetry()
        }
    }

    fun stopAudioStream() {
        scope.launch {
            if (_bridgeState.value.active) {
                stopRootLoopbackBridgeInternal(updateServiceNotification = true)
                return@launch
            }
            NativeUsbAudioBridge.stopStream()
            _telemetry.value = NativeUsbAudioBridge.getTelemetry()
            val state = _connectionState.value
            if (state is DacConnectionState.Connected) {
                RootDacService.updateStatus(
                    appContext,
                    "${state.profile.productName} (${state.profile.uacVersion}) · 待机"
                )
            }
        }
    }

    fun setHardwareVolumeDb(db: Double) {
        val session = activeSession
        val minDb = session?.profile?.volumeMinDb ?: -60.0
        val maxDb = session?.profile?.volumeMaxDb ?: 0.0
        val clamped = db.coerceIn(minDb, maxDb)
        _volumeDb.value = clamped
        volumeInputFlow.tryEmit(clamped)
    }

    fun commitHardwareVolumeDb() {
        val finalDb = _volumeDb.value
        scope.launch {
            applyHardwareVolumeDb(finalDb)
        }
    }

    private fun applyHardwareVolumeDb(db: Double) {
        if (activeSession == null) return
        if (db == lastAppliedVolumeDb) return
        val res = NativeUsbAudioBridge.setHardwareVolumeDb(db)
        if (res.isSuccess) {
            lastAppliedVolumeDb = db
        } else {
            appendLog(2, "KT", "硬件音量写入失败: ${res.exceptionOrNull()?.message}")
        }
    }

    fun setPreferredBridgeBitDepth(bits: Int) {
        val target = when {
            bits <= 0 -> 0
            bits >= 32 -> 32
            else -> 24
        }
        if (_preferredBridgeBitDepth.value == target) return
        _preferredBridgeBitDepth.value = target
        val desc = when (target) {
            0 -> "自动原生 (16/24/32-bit)"
            32 -> "32-bit (S32_LE)"
            else -> "24-bit (S24_3LE)"
        }
        appendLog(1, "KT", "切换 DAC 位深模式 -> $desc")

        val state = _bridgeState.value
        if (state.active) {
            scope.launch {
                if (state.engineMode == RootCaptureEngineMode.LSPOSED_DIRECT) {
                    val activeEvt = lspDirectServer.status.value.activeFormat
                    if (activeEvt != null) {
                        lspDirectServer.resetConfiguredFormat()
                        reconfigureLspDirectForEvent(activeEvt)
                    }
                } else {
                    val curSpec = state.sourceSpec
                    if (curSpec != null) {
                        reconfigureRootSubmixForSpec(curSpec)
                    }
                }
            }
        }
    }

    fun setBufferDurationMs(ms: Int) {
        val clamped = ms.coerceIn(100, 600)
        _bufferDurationMs.value = clamped
        scope.launch {
            NativeUsbAudioBridge.setBufferDurationMs(clamped)
            _telemetry.value = NativeUsbAudioBridge.getTelemetry()
        }
    }

    // -------------------------------------------------------------------------
    // Exclusive USB DAC Connection Lifecycle (Two-Stage IF#2 -> IF#0)
    // -------------------------------------------------------------------------

    private fun connectWithPermission(
        device: UsbDevice,
        autoStartBridgeAfterConnect: Boolean = false
    ) {
        scope.launch {
            disconnectInternal("切换/重新接管 USB DAC")

            val name = deviceDisplayName(device)
            _connectionState.value = DacConnectionState.Connecting(name)

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                val msg = "UsbManager.openDevice() 返回 null ($name)"
                appendLog(3, "KT", msg)
                _connectionState.value = DacConnectionState.Error(msg)
                return@launch
            }

            val fd = connection.fileDescriptor
            appendLog(1, "KT", "正在接管 $name (fd=$fd)...")

            val desiredVolDb = _volumeDb.value
            val result = NativeUsbAudioBridge.openAndInspect(
                fd = fd,
                vendorId = device.vendorId,
                productId = device.productId,
                fallbackName = name,
                initialVolumeDb = desiredVolDb
            )

            when (result) {
                is InspectResult.Success -> {
                    activeSession = ActiveUsbSession(device, connection, result.profile)
                    NativeUsbAudioBridge.setBufferDurationMs(_bufferDurationMs.value)
                    val initialTel = NativeUsbAudioBridge.getTelemetry()
                    _telemetry.value = initialTel
                    _volumeDb.value = initialTel.volumeDb
                    lastAppliedVolumeDb = initialTel.volumeDb

                    appendLog(
                        1,
                        "KT",
                        "✓ 独占接管完成: ${result.profile.productName} [${result.profile.uacVersion}] · 支持采样率=${result.profile.allSupportedSampleRates}"
                    )
                    RootDacService.startConnectedDevice(
                        appContext,
                        "${result.profile.productName} (${result.profile.uacVersion})"
                    )
                    _connectionState.value = DacConnectionState.Connected(
                        device = device,
                        profile = result.profile,
                        fullReport = result.report
                    )
                    startTelemetryPolling()
                    refreshDevices()
                    if (autoStartBridgeAfterConnect) {
                        startBridgeWithActiveSessionInternal()
                    }
                }
                is InspectResult.Failure -> {
                    appendLog(3, "KT", "Native openAndInspect 失败: ${result.error}")
                    NativeUsbAudioBridge.close(restoreUnityVolume = false, resetUsbPort = false)
                    runCatching { connection.close() }
                    _connectionState.value = DacConnectionState.Error(result.error)
                }
            }
        }
    }

    private fun startTelemetryPolling() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            var lastReportedUnderruns = 0L
            var lastUnderrunLogAt = 0L
            while (isActive && activeSession != null) {
                val tel = NativeUsbAudioBridge.getTelemetry()
                _telemetry.value = tel
                if (!tel.streaming) {
                    lastReportedUnderruns = 0L
                } else if (tel.underruns > lastReportedUnderruns) {
                    val now = System.currentTimeMillis()
                    if (now - lastUnderrunLogAt >= 2000L) {
                        val delta = tel.underruns - lastReportedUnderruns
                        lastReportedUnderruns = tel.underruns
                        lastUnderrunLogAt = now
                        appendLog(
                            2,
                            "C++",
                            "[UNDERRUN] SPSC 欠载 +$delta (累计=${tel.underruns}), 缓冲=${"%.1f".format(tel.bufferedMs)}ms"
                        )
                    }
                }
                delay(250)
            }
        }
    }

    fun disconnect(reason: String = "用户主动释放") {
        scope.launch {
            disconnectInternal(reason)
            _connectionState.value = DacConnectionState.Disconnected
            _telemetry.value = StreamTelemetry()
            refreshDevices()
        }
    }

    private suspend fun disconnectInternal(reason: String) {
        stopRootLoopbackBridgeInternal(updateServiceNotification = false)
        telemetryJob?.cancel()
        telemetryJob = null

        val session = activeSession ?: return
        activeSession = null
        appendLog(1, "KT", "正在释放 USB DAC ($reason)...")

        NativeUsbAudioBridge.close(restoreUnityVolume = true, resetUsbPort = false)

        runCatching { session.connection.close() }
        RootDacService.stop(appContext)
        appendLog(1, "KT", "✓ USB DAC 已归位 Alt#0 + 恢复 0.00 dB 并交还系统驱动")
    }

    private fun collectUniqueAudioInterfaces(device: UsbDevice): List<UsbInterface> {
        val byId = linkedMapOf<Int, UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                (intf.interfaceSubclass == USB_AUDIO_SUBCLASS_CONTROL ||
                    intf.interfaceSubclass == USB_AUDIO_SUBCLASS_STREAMING)
            ) {
                val existing = byId[intf.id]
                if (existing == null || intf.alternateSetting == 0) {
                    byId[intf.id] = intf
                }
            }
        }
        return byId.values.toList()
    }

    private fun deviceDisplayName(device: UsbDevice): String {
        val product = device.productName?.takeIf { it.isNotBlank() }
        val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() }
        return when {
            product != null && manufacturer != null && !product.startsWith(manufacturer, ignoreCase = true) ->
                "$manufacturer $product"
            product != null -> product
            else -> "USB DAC (${"%04X:%04X".format(device.vendorId, device.productId)})"
        }
    }

    private fun appendLog(level: Int, source: String, message: String) {
        when (level) {
            0 -> Log.d(TAG, "[$source] $message")
            1 -> Log.i(TAG, "[$source] $message")
            2 -> Log.w(TAG, "[$source] $message")
            else -> Log.e(TAG, "[$source] $message")
        }
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            source = source,
            message = message
        )
        _logs.update { current ->
            if (current.size >= MAX_LOG_LINES) {
                current.drop(current.size - MAX_LOG_LINES + 1) + entry
            } else {
                current + entry
            }
        }
    }
}
