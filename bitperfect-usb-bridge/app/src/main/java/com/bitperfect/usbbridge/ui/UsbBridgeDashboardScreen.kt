package com.bitperfect.usbbridge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitperfect.usbbridge.BuildConfig
import com.bitperfect.usbbridge.capture.InstalledAudioApp
import com.bitperfect.usbbridge.capture.ResolvedUidApp
import com.bitperfect.usbbridge.i18n.LanguagePref
import com.bitperfect.usbbridge.i18n.UsbBridgeStrings
import com.bitperfect.usbbridge.nativebridge.StreamTelemetry
import com.bitperfect.usbbridge.platform.SocCompatibilityProfile
import com.bitperfect.usbbridge.shizuku.RemoteSubmixProfile
import com.bitperfect.usbbridge.shizuku.ShizukuConnectionState
import com.bitperfect.usbbridge.shizuku.SnifferStatus
import com.bitperfect.usbbridge.usb.AudioBridgeState
import com.bitperfect.usbbridge.usb.BridgeOperatingMode
import com.bitperfect.usbbridge.usb.CaptureScopeMode
import com.bitperfect.usbbridge.usb.DacConnectionState
import com.bitperfect.usbbridge.usb.DiscoveredUsbAudioDevice
import com.bitperfect.usbbridge.usb.LogEntry
import com.bitperfect.usbbridge.usb.SineTestPreset
import com.bitperfect.usbbridge.usb.SystemVolumeState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Deep Ocean Obsidian + Glacier Cyan Hi-Fi Palette
private val BgObsidian = Color(0xFF070B12)
private val CardBg = Color(0xFF0E1522)
private val CardBorder = Color(0xFF1C293D)
private val TileBg = Color(0xFF090F19)
private val TileBorder = Color(0xFF162234)
private val CyanPrimary = Color(0xFF22D3EE)
private val CyanDimBg = Color(0xFF0B2938)
private val CyanBorder = Color(0xFF196880)
private val EmeraldLive = Color(0xFF2AD6A5)
private val EmeraldDimBg = Color(0xFF0A2A24)
private val EmeraldBorder = Color(0xFF176653)
private val AmberWarn = Color(0xFFFBBF24)
private val AmberDimBg = Color(0xFF2B200B)
private val AmberBorder = Color(0xFF6E5015)
private val CoralDanger = Color(0xFFFB7185)
private val CoralDimBg = Color(0xFF2D121B)
private val CoralBorder = Color(0xFF73263A)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val TextMuted = Color(0xFF64748B)

private val TightTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

private val SINE_PRESETS = listOf(
    SineTestPreset(44100, 24, "44.1 kHz"),
    SineTestPreset(48000, 24, "48 kHz"),
    SineTestPreset(96000, 24, "96 kHz"),
    SineTestPreset(192000, 24, "192 kHz")
)

// 60ms = Rhythm-game low-latency mode; 150 / 250 / 400ms = Hi-Fi jitter-free modes
private val BUFFER_PRESETS_MS = listOf(60, 150, 250, 400)

@Composable
fun UsbBridgeDashboardScreen(
    strings: UsbBridgeStrings,
    languagePref: LanguagePref,
    socProfile: SocCompatibilityProfile,
    remoteSubmixProfile: RemoteSubmixProfile?,
    shizukuState: ShizukuConnectionState,
    operatingMode: BridgeOperatingMode,
    captureScopeMode: CaptureScopeMode,
    selectedApp: InstalledAudioApp?,
    trackedPlayer: ResolvedUidApp?,
    installedApps: List<InstalledAudioApp>,
    isScanningApps: Boolean,
    preferredLaunchLabel: String?,
    manualSampleRate: Int,
    systemMusicVolume: SystemVolumeState,
    isBatteryOptIgnored: Boolean,
    xringHubDisclaimerActive: Boolean,
    xringHubDisclaimerAccepted: Boolean,
    xringDirectOverrideActive: Boolean,
    devices: List<DiscoveredUsbAudioDevice>,
    connectionState: DacConnectionState,
    telemetry: StreamTelemetry,
    bridgeState: AudioBridgeState,
    snifferStatus: SnifferStatus,
    selectedPreset: SineTestPreset,
    volumeDb: Double,
    bufferDurationMs: Int,
    preferredBridgeBitDepth: Int,
    logs: List<LogEntry>,
    onToggleLanguage: () -> Unit,
    onSelectLanguagePref: (LanguagePref) -> Unit,
    onAcceptXringHubDisclaimer: () -> Unit,
    onUnlockXringDirectForTesting: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onSelectOperatingMode: (BridgeOperatingMode) -> Unit,
    onSelectCaptureScopeMode: (CaptureScopeMode) -> Unit,
    onSelectTargetApp: (InstalledAudioApp) -> Unit,
    onRefreshInstalledApps: () -> Unit,
    onSelectManualSampleRate: (Int) -> Unit,
    onMaximizeSystemVolume: () -> Unit,
    onRequestBatteryOptExemption: () -> Unit,
    onOpenPreferredPlayer: () -> Unit,
    onConnectDevice: (DiscoveredUsbAudioDevice) -> Unit,
    onClaimArmedUsbNow: () -> Unit,
    onDisconnect: () -> Unit,
    onStartAudioBridge: () -> Unit,
    onStopAudioBridge: () -> Unit,
    onQuickResync: () -> Unit,
    onSelectBridgeBitDepth: (Int) -> Unit,
    onDumpAndCopySnapshot: () -> Unit,
    onToggleSineTest: () -> Unit,
    onSelectSinePreset: (SineTestPreset) -> Unit,
    onVolumeChangeDb: (Double) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onBufferMsChange: (Int) -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onCopyReport: (String) -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) }
    var showAppPickerDialog by remember { mutableStateOf(false) }
    var showXringDirectUnlockDialog by remember { mutableStateOf(false) }
    var pendingHubAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun runWithXringHubDisclaimer(action: () -> Unit) {
        if (xringHubDisclaimerActive && !xringHubDisclaimerAccepted) {
            pendingHubAction = action
        } else {
            action()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgObsidian)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HeaderSection(
                    s = strings,
                    socProfile = socProfile,
                    remoteSubmixProfile = remoteSubmixProfile,
                    connectionState = connectionState,
                    bridgeState = bridgeState,
                    telemetry = telemetry,
                    onToggleLanguage = onToggleLanguage
                )
            }

            if (xringDirectOverrideActive) {
                item {
                    XringDirectOverrideBanner(s = strings)
                }
            } else if (xringHubDisclaimerActive) {
                item {
                    XringHubDisclaimerBanner(s = strings)
                }
            }

            if (!isBatteryOptIgnored) {
                item {
                    BatteryGuardBanner(
                        s = strings,
                        onRequestExemption = onRequestBatteryOptExemption
                    )
                }
            }

            item {
                UsbHardwareCard(
                    s = strings,
                    devices = devices,
                    connectionState = connectionState,
                    bridgeActive = bridgeState.active,
                    onRefreshDevices = onRefreshDevices,
                    onConnectDevice = { dev -> runWithXringHubDisclaimer { onConnectDevice(dev) } },
                    onClaimArmedUsbNow = { runWithXringHubDisclaimer(onClaimArmedUsbNow) },
                    onRequestXringDirectUnlock = { showXringDirectUnlockDialog = true },
                    onDisconnect = onDisconnect
                )
            }

            item {
                AudioBridgeConsoleCard(
                    s = strings,
                    shizukuState = shizukuState,
                    operatingMode = operatingMode,
                    captureScopeMode = captureScopeMode,
                    selectedApp = selectedApp,
                    trackedPlayer = trackedPlayer,
                    preferredLaunchLabel = preferredLaunchLabel,
                    manualSampleRate = manualSampleRate,
                    systemMusicVolume = systemMusicVolume,
                    connectionState = connectionState,
                    bridgeState = bridgeState,
                    snifferStatus = snifferStatus,
                    telemetry = telemetry,
                    preferredBitDepth = preferredBridgeBitDepth,
                    onSelectOperatingMode = onSelectOperatingMode,
                    onSelectCaptureScopeMode = { nextScope ->
                        onSelectCaptureScopeMode(nextScope)
                        if (nextScope == CaptureScopeMode.SINGLE_APP && selectedApp == null) {
                            if (installedApps.isEmpty()) onRefreshInstalledApps()
                            showAppPickerDialog = true
                        }
                    },
                    onOpenAppPicker = {
                        if (installedApps.isEmpty()) onRefreshInstalledApps()
                        showAppPickerDialog = true
                    },
                    onSelectManualSampleRate = onSelectManualSampleRate,
                    onRefreshShizuku = onRefreshShizuku,
                    onRequestShizukuPermission = onRequestShizukuPermission,
                    onMaximizeSystemVolume = onMaximizeSystemVolume,
                    onStartBridge = {
                        if (captureScopeMode == CaptureScopeMode.SINGLE_APP && selectedApp == null) {
                            if (installedApps.isEmpty()) onRefreshInstalledApps()
                            showAppPickerDialog = true
                        } else {
                            runWithXringHubDisclaimer(onStartAudioBridge)
                        }
                    },
                    onStopBridge = onStopAudioBridge,
                    onQuickResync = onQuickResync,
                    onOpenPlayerOrPicker = {
                        if (preferredLaunchLabel != null) {
                            onOpenPreferredPlayer()
                        } else {
                            if (installedApps.isEmpty()) onRefreshInstalledApps()
                            showAppPickerDialog = true
                        }
                    }
                )
            }

            item {
                GainAndBufferCard(
                    s = strings,
                    connectionState = connectionState,
                    volumeDb = volumeDb,
                    preferredBitDepth = preferredBridgeBitDepth,
                    bufferDurationMs = bufferDurationMs,
                    onVolumeChangeDb = onVolumeChangeDb,
                    onVolumeChangeFinished = onVolumeChangeFinished,
                    onSelectBitDepth = onSelectBridgeBitDepth,
                    onSelectBufferMs = onBufferMsChange
                )
            }

            item {
                WorkbenchCard(
                    s = strings,
                    languagePref = languagePref,
                    socProfile = socProfile,
                    remoteSubmixProfile = remoteSubmixProfile,
                    onSelectLanguagePref = onSelectLanguagePref,
                    activeTab = activeTab,
                    onSelectTab = { activeTab = it },
                    logs = logs,
                    telemetry = telemetry,
                    selectedPreset = selectedPreset,
                    connectionState = connectionState,
                    onCopyLogs = onCopyLogs,
                    onDumpSnapshot = onDumpAndCopySnapshot,
                    onClearLogs = onClearLogs,
                    onToggleSineTest = { runWithXringHubDisclaimer(onToggleSineTest) },
                    onSelectSinePreset = { preset -> runWithXringHubDisclaimer { onSelectSinePreset(preset) } },
                    onClaimArmedUsbNow = { runWithXringHubDisclaimer(onClaimArmedUsbNow) },
                    onCopyReport = onCopyReport
                )
            }
        }
    }

    if (showAppPickerDialog) {
        AppPickerDialog(
            s = strings,
            installedApps = installedApps,
            selectedPackageName = selectedApp?.packageName,
            isScanning = isScanningApps,
            onRefresh = onRefreshInstalledApps,
            onSelectApp = { app ->
                onSelectTargetApp(app)
                showAppPickerDialog = false
            },
            onDismiss = { showAppPickerDialog = false }
        )
    }

    val pendingAction = pendingHubAction
    if (pendingAction != null) {
        XringHubDisclaimerDialog(
            s = strings,
            onDismiss = { pendingHubAction = null },
            onAccept = {
                pendingHubAction = null
                onAcceptXringHubDisclaimer()
                pendingAction()
            }
        )
    }

    if (showXringDirectUnlockDialog) {
        XringDirectUnlockDialog(
            s = strings,
            onDismiss = { showXringDirectUnlockDialog = false },
            onConfirmUnlock = {
                showXringDirectUnlockDialog = false
                onUnlockXringDirectForTesting()
            }
        )
    }
}

@Composable
private fun HeaderSection(
    s: UsbBridgeStrings,
    socProfile: SocCompatibilityProfile,
    remoteSubmixProfile: RemoteSubmixProfile?,
    connectionState: DacConnectionState,
    bridgeState: AudioBridgeState,
    telemetry: StreamTelemetry,
    onToggleLanguage: () -> Unit
) {
    val (badgeText, badgeDot, badgeBg, badgeBorder) = when {
        connectionState is DacConnectionState.BlockedBySoc ->
            Quad(s.badgeBlocked, CoralDanger, CoralDimBg, CoralBorder)
        bridgeState.active && telemetry.streaming ->
            Quad(s.badgeStreaming, EmeraldLive, EmeraldDimBg, EmeraldBorder)
        bridgeState.active ->
            Quad(s.badgePrefill, CyanPrimary, CyanDimBg, CyanBorder)
        telemetry.sineRunning ->
            Quad(s.badgeSineTest, CyanPrimary, CyanDimBg, CyanBorder)
        connectionState is DacConnectionState.Connected ->
            Quad(s.badgeClaimed, CyanPrimary, CyanDimBg, CyanBorder)
        connectionState is DacConnectionState.ArmedStandby ->
            Quad(s.badgeReady, CyanPrimary, CyanDimBg, CyanBorder)
        else ->
            Quad(s.badgeIdle, TextMuted, TileBg, CardBorder)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AutoFitSingleLineText(
                text = "BitPerfect USB Bridge",
                color = TextPrimary,
                maxFontSize = 19.sp,
                minFontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )

            // 1-tap Language Switcher Pill
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(TileBg)
                    .border(1.dp, CyanBorder.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onToggleLanguage)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = s.langTogglePill,
                    color = CyanPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TightTextStyle
                )
            }

            // Status Pill
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBg)
                    .border(1.dp, badgeBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(CircleShape)
                        .background(badgeDot)
                )
                Text(
                    text = badgeText,
                    color = TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TightTextStyle
                )
            }
        }

        Text(
            text = "v${BuildConfig.VERSION_NAME} · ${s.subtitle}",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            style = TightTextStyle
        )

        // Compact Universal SoC + r_submix Capability Pill Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TileBg)
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = socProfile.headerBadge(s.isZh),
                    color = CyanPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    style = TightTextStyle
                )
            }

            if (remoteSubmixProfile != null && remoteSubmixProfile.maxSupportedRate > 48000) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(EmeraldDimBg)
                        .border(1.dp, EmeraldBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = s.submixHiResBadge(remoteSubmixProfile.maxSupportedRate),
                        color = EmeraldLive,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TightTextStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryGuardBanner(
    s: UsbBridgeStrings,
    onRequestExemption: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AmberDimBg)
            .border(1.dp, AmberBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = s.batteryTitle,
                color = AmberWarn,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp,
                style = TightTextStyle
            )
            Text(
                text = s.batterySub,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                style = TightTextStyle
            )
        }
        UniformButton(
            text = s.batteryBtn,
            onClick = onRequestExemption,
            height = 34.dp,
            style = BtnStyle.Warning,
            modifier = Modifier.width(76.dp)
        )
    }
}

@Composable
private fun XringDirectOverrideBanner(s: UsbBridgeStrings) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CoralDimBg)
            .border(1.5.dp, CoralDanger, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = s.xringDirectBannerTitle,
            color = CoralDanger,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 17.sp,
            style = TightTextStyle
        )
        Text(
            text = s.xringDirectBannerBody,
            color = TextPrimary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            style = TightTextStyle
        )
    }
}

@Composable
private fun XringDirectUnlockDialog(
    s: UsbBridgeStrings,
    onDismiss: () -> Unit,
    onConfirmUnlock: () -> Unit
) {
    var riskChecked by remember { mutableStateOf(false) }
    var countdownSec by remember { mutableIntStateOf(3) }

    LaunchedEffect(Unit) {
        while (countdownSec > 0) {
            delay(1000L)
            countdownSec -= 1
        }
    }

    val canConfirm = riskChecked && countdownSec == 0
    val confirmLabel = when {
        countdownSec > 0 -> s.xringDirectDialogWaitBtn(countdownSec)
        !riskChecked -> s.xringDirectDialogCheckFirstBtn
        else -> s.xringDirectDialogConfirmBtn
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.5.dp, CoralDanger, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CoralDimBg)
                    .border(1.dp, CoralBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = s.xringDirectDialogTitle,
                    color = CoralDanger,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                    style = TightTextStyle
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TileBg)
                    .border(1.dp, CoralBorder.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = s.xringDirectDialogBody,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    style = TightTextStyle
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (riskChecked) CoralDimBg else TileBg)
                    .border(
                        width = 1.dp,
                        color = if (riskChecked) CoralDanger else CoralBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { riskChecked = !riskChecked }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (riskChecked) CoralDanger else BgObsidian)
                        .border(
                            width = 1.5.dp,
                            color = if (riskChecked) CoralDanger else TextMuted,
                            shape = RoundedCornerShape(5.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (riskChecked) {
                        Text(
                            text = "✓",
                            color = BgObsidian,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            style = TightTextStyle
                        )
                    }
                }
                Text(
                    text = s.xringDirectDialogCheckbox,
                    color = if (riskChecked) TextPrimary else TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = if (riskChecked) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = 16.sp,
                    style = TightTextStyle,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UniformButton(
                    text = s.xringHubDialogCancel,
                    onClick = onDismiss,
                    height = 42.dp,
                    style = BtnStyle.Ghost,
                    modifier = Modifier.weight(0.75f)
                )
                UniformButton(
                    text = confirmLabel,
                    onClick = onConfirmUnlock,
                    enabled = canConfirm,
                    height = 42.dp,
                    style = BtnStyle.Danger,
                    modifier = Modifier.weight(1.45f)
                )
            }
        }
    }
}

@Composable
private fun XringHubDisclaimerBanner(s: UsbBridgeStrings) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AmberDimBg)
            .border(1.dp, AmberBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = s.xringHubBannerTitle,
            color = AmberWarn,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 17.sp,
            style = TightTextStyle
        )
        Text(
            text = s.xringHubBannerBody,
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            style = TightTextStyle
        )
    }
}

@Composable
private fun XringHubDisclaimerDialog(
    s: UsbBridgeStrings,
    onDismiss: () -> Unit,
    onAccept: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.dp, AmberBorder, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = s.xringHubDialogTitle,
                color = AmberWarn,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
                style = TightTextStyle
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TileBg)
                    .border(1.dp, TileBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = s.xringHubDialogBody,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    style = TightTextStyle
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UniformButton(
                    text = s.xringHubDialogCancel,
                    onClick = onDismiss,
                    height = 40.dp,
                    style = BtnStyle.Ghost,
                    modifier = Modifier.weight(0.85f)
                )
                UniformButton(
                    text = s.xringHubDialogAccept,
                    onClick = onAccept,
                    height = 40.dp,
                    style = BtnStyle.Warning,
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
    }
}

@Composable
private fun UsbHardwareCard(
    s: UsbBridgeStrings,
    devices: List<DiscoveredUsbAudioDevice>,
    connectionState: DacConnectionState,
    bridgeActive: Boolean,
    onRefreshDevices: () -> Unit,
    onConnectDevice: (DiscoveredUsbAudioDevice) -> Unit,
    onClaimArmedUsbNow: () -> Unit,
    onRequestXringDirectUnlock: () -> Unit,
    onDisconnect: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionTitle(s.usbCardTitle)
            val subTag = when (connectionState) {
                is DacConnectionState.Connected ->
                    "${connectionState.profile.uacVersion} · ${connectionState.profile.vidPidHex}"
                is DacConnectionState.ArmedStandby ->
                    s.usbStandbyTag(connectionState.vidPid)
                is DacConnectionState.BlockedBySoc ->
                    "${s.xringBlockedTag} · ${connectionState.vidPid}"
                else -> s.usbFoundCount(devices.size)
            }
            Text(
                text = subTag,
                color = if (connectionState is DacConnectionState.BlockedBySoc) CoralDanger else TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                style = TightTextStyle
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (connectionState) {
            is DacConnectionState.BlockedBySoc -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CoralDimBg)
                        .border(1.dp, CoralBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = s.xringBlockedTitle,
                            color = CoralDanger,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 18.sp,
                            style = TightTextStyle
                        )
                        Text(
                            text = "${connectionState.displayName} (${connectionState.vidPid}) · ${connectionState.topologyDetail}",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                            style = TightTextStyle
                        )
                        Text(
                            text = if (connectionState.circuitBreakerTripped) {
                                s.xringBlockedBreakerBody
                            } else {
                                s.xringBlockedBody
                            },
                            color = TextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.5.sp,
                            style = TightTextStyle
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UniformButton(
                        text = s.btnScanUsb,
                        onClick = onRefreshDevices,
                        height = 38.dp,
                        style = BtnStyle.Secondary,
                        modifier = Modifier.weight(0.9f)
                    )
                    UniformButton(
                        text = s.btnXringHighRiskUnlock,
                        onClick = onRequestXringDirectUnlock,
                        enabled = true,
                        height = 38.dp,
                        style = BtnStyle.Danger,
                        modifier = Modifier.weight(1.3f)
                    )
                }
            }

            is DacConnectionState.Connected -> {
                val p = connectionState.profile
                val maxRateKhz = "%.1f".format((p.allSupportedSampleRates.maxOrNull() ?: 192000) / 1000.0)
                val shortSpeed = compactUsbSpeed(p.usbSpeed)
                val fuLabel = if (p.hasHardwareVolume) "FU#${p.featureUnitId}" else s.usbHwVolNone
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TileBg)
                        .border(1.dp, CyanBorder.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = p.productName.ifBlank { "USB Audio DAC" },
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 19.sp,
                            style = TightTextStyle
                        )
                        Text(
                            text = s.usbSpecLine(shortSpeed, maxRateKhz, fuLabel),
                            color = CyanPrimary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            style = TightTextStyle
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UniformButton(
                        text = s.btnRescan,
                        onClick = onRefreshDevices,
                        enabled = !bridgeActive,
                        height = 38.dp,
                        style = BtnStyle.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    UniformButton(
                        text = s.btnReleaseDac,
                        onClick = onDisconnect,
                        height = 38.dp,
                        style = BtnStyle.Danger,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            is DacConnectionState.ArmedStandby -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TileBg)
                        .border(1.dp, CyanBorder.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = connectionState.displayName,
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 19.sp,
                            style = TightTextStyle
                        )
                        Text(
                            text = s.usbArmedReadyHint,
                            color = CyanPrimary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            style = TightTextStyle
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UniformButton(
                        text = s.btnInspectHw,
                        onClick = onClaimArmedUsbNow,
                        height = 38.dp,
                        style = BtnStyle.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    UniformButton(
                        text = s.btnReleaseStandby,
                        onClick = onDisconnect,
                        height = 38.dp,
                        style = BtnStyle.Ghost,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            else -> {
                val firstDev = devices.firstOrNull()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TileBg)
                        .border(1.dp, TileBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (firstDev != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = firstDev.displayName,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 18.sp,
                                style = TightTextStyle
                            )
                            Text(
                                text = "VID:PID ${firstDev.vidPid} · ${if (firstDev.hasPermission) s.usbPermGranted else s.usbPermNeeded}",
                                color = TextSecondary,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                style = TightTextStyle
                            )
                        }
                    } else {
                        val hint = when (connectionState) {
                            is DacConnectionState.RequestingPermission -> s.usbWaitingDialog
                            is DacConnectionState.Connecting -> s.usbConnecting
                            is DacConnectionState.Error -> s.usbConnectErr(connectionState.message)
                            else -> s.usbPlugInHint
                        }
                        Text(
                            text = hint,
                            color = if (connectionState is DacConnectionState.Error) CoralDanger else TextSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            style = TightTextStyle
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UniformButton(
                        text = s.btnScanUsb,
                        onClick = onRefreshDevices,
                        height = 38.dp,
                        style = BtnStyle.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    val isBlockedFirst = firstDev?.isBlockedDirectXring == true
                    UniformButton(
                        text = when {
                            isBlockedFirst -> s.btnXringHighRiskUnlock
                            firstDev?.hasPermission == true -> s.btnArmReady
                            else -> s.btnGrantConnect
                        },
                        onClick = {
                            if (isBlockedFirst) {
                                onRequestXringDirectUnlock()
                            } else {
                                firstDev?.let(onConnectDevice)
                            }
                        },
                        enabled = firstDev != null,
                        height = 38.dp,
                        style = if (isBlockedFirst) BtnStyle.Danger else BtnStyle.Primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioBridgeConsoleCard(
    s: UsbBridgeStrings,
    shizukuState: ShizukuConnectionState,
    operatingMode: BridgeOperatingMode,
    captureScopeMode: CaptureScopeMode,
    selectedApp: InstalledAudioApp?,
    trackedPlayer: ResolvedUidApp?,
    preferredLaunchLabel: String?,
    manualSampleRate: Int,
    systemMusicVolume: SystemVolumeState,
    connectionState: DacConnectionState,
    bridgeState: AudioBridgeState,
    snifferStatus: SnifferStatus,
    telemetry: StreamTelemetry,
    preferredBitDepth: Int,
    onSelectOperatingMode: (BridgeOperatingMode) -> Unit,
    onSelectCaptureScopeMode: (CaptureScopeMode) -> Unit,
    onOpenAppPicker: () -> Unit,
    onSelectManualSampleRate: (Int) -> Unit,
    onRefreshShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onMaximizeSystemVolume: () -> Unit,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onQuickResync: () -> Unit,
    onOpenPlayerOrPicker: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionTitle(s.bridgeCardTitle)
            Text(
                text = if (operatingMode == BridgeOperatingMode.SHIZUKU_AUTO) s.modeBadgeAuto else s.modeBadgeManual,
                color = CyanPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                style = TightTextStyle
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. Capture Scope 2-Way Segmented Bar (Global Auto vs Single App Lock)
        SegmentedRow(
            options = listOf(s.scopeGlobalTab, s.scopeSingleTab),
            selectedIndex = if (captureScopeMode == CaptureScopeMode.SINGLE_APP) 1 else 0,
            onSelect = { idx ->
                onSelectCaptureScopeMode(
                    if (idx == 1) CaptureScopeMode.SINGLE_APP else CaptureScopeMode.GLOBAL_MEDIA
                )
            },
            height = 36.dp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Active / Locked Player Telemetry & Picker Strip
        PlayerScopeStrip(
            s = s,
            captureScopeMode = captureScopeMode,
            selectedApp = selectedApp,
            trackedPlayer = trackedPlayer,
            bridgeState = bridgeState,
            onOpenAppPicker = onOpenAppPicker
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Operating Mode 2-Way Segmented Bar (Manual vs Shizuku Auto)
        SegmentedRow(
            options = listOf(s.modeManualTab, s.modeShizukuTab),
            selectedIndex = if (operatingMode == BridgeOperatingMode.SHIZUKU_AUTO) 1 else 0,
            onSelect = { idx ->
                onSelectOperatingMode(
                    if (idx == 1) BridgeOperatingMode.SHIZUKU_AUTO else BridgeOperatingMode.NON_ROOT_MANUAL
                )
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (operatingMode == BridgeOperatingMode.NON_ROOT_MANUAL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RateOptionTile(
                    title = s.rate44Title,
                    subtitle = s.rate44Sub,
                    selected = manualSampleRate == 44100,
                    onClick = { onSelectManualSampleRate(44100) },
                    modifier = Modifier.weight(1f)
                )
                RateOptionTile(
                    title = s.rate48Title,
                    subtitle = s.rate48Sub,
                    selected = manualSampleRate == 48000,
                    onClick = { onSelectManualSampleRate(48000) },
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            val shizukuTitle = when (shizukuState) {
                is ShizukuConnectionState.NotRunning -> s.shizukuNotRunning
                is ShizukuConnectionState.PermissionRequired -> s.shizukuNeedPerm
                is ShizukuConnectionState.Binding -> s.shizukuBinding
                is ShizukuConnectionState.Ready -> s.shizukuReadyBadge(shizukuState.uid)
                is ShizukuConnectionState.Error -> s.shizukuErrBadge
            }
            val shizukuSub = if (shizukuState.isReady) {
                if (snifferStatus.running) {
                    val detected = snifferStatus.lastDetectedSpec
                    s.shizukuActiveHint(detected?.appLabel, detected?.sampleRate)
                } else {
                    s.shizukuIdleHint
                }
            } else {
                s.shizukuFallbackHint
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TileBg)
                    .border(
                        1.dp,
                        if (shizukuState.isReady) EmeraldBorder.copy(alpha = 0.6f) else AmberBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = shizukuTitle,
                        color = if (shizukuState.isReady) EmeraldLive else AmberWarn,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = shizukuSub,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        style = TightTextStyle
                    )
                }
                if (shizukuState.isReady) {
                    UniformButton(
                        text = s.btnRefresh,
                        onClick = onRefreshShizuku,
                        height = 32.dp,
                        style = BtnStyle.Ghost,
                        modifier = Modifier.width(72.dp)
                    )
                } else {
                    UniformButton(
                        text = s.btnAuthorize,
                        onClick = onRequestShizukuPermission,
                        height = 32.dp,
                        style = BtnStyle.Primary,
                        modifier = Modifier.width(72.dp)
                    )
                }
            }
        }

        // System Media Volume Guard (catches 0/150 muted AudioFlinger capture!)
        if ((bridgeState.active && !systemMusicVolume.isFull) || systemMusicVolume.isMuted) {
            Spacer(modifier = Modifier.height(10.dp))
            val isZero = systemMusicVolume.isMuted
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isZero) CoralDimBg else AmberDimBg)
                    .border(1.dp, if (isZero) CoralBorder else AmberBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = if (isZero) {
                            s.sysVolZeroTitle(systemMusicVolume.max)
                        } else {
                            s.sysVolLowTitle(systemMusicVolume.current, systemMusicVolume.max)
                        },
                        color = if (isZero) CoralDanger else AmberWarn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = s.sysVolSub,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        style = TightTextStyle
                    )
                }
                UniformButton(
                    text = s.btnMaxVol,
                    onClick = onMaximizeSystemVolume,
                    height = 32.dp,
                    style = if (isZero) BtnStyle.Danger else BtnStyle.Warning,
                    modifier = Modifier.width(82.dp)
                )
            }
        }

        // Detect Root+LSPosed sibling app's -84.3 dBFS ultrasonic dither signature!
        if (bridgeState.active && telemetry.streaming && telemetry.peakDbfs in -85.2..-83.4) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CoralDimBg)
                    .border(1.dp, CoralBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = s.rootConflictAlert,
                    color = CoralDanger,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    style = TightTextStyle
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3-Column Dynamic Equal-Height Signal Matrix (IntrinsicSize.Max -> ZERO bottom clipping!)
        val srcRateText = when {
            bridgeState.active && bridgeState.sourceSpec != null ->
                formatKhz(bridgeState.sourceSpec.sampleRate)
            operatingMode == BridgeOperatingMode.NON_ROOT_MANUAL ->
                formatKhz(manualSampleRate)
            else -> "-- kHz"
        }
        val srcSubText = when {
            !bridgeState.active -> s.tileSourceIdle
            operatingMode == BridgeOperatingMode.SHIZUKU_AUTO && shizukuState.isReady -> s.tileSourceShizuku
            else -> s.tileSourceManual
        }

        val dacRateText = when {
            telemetry.streaming && telemetry.sampleRate > 0 ->
                formatKhz(telemetry.sampleRate)
            bridgeState.dacHardware != null ->
                formatKhz(bridgeState.dacHardware.dacSampleRate)
            else -> "-- kHz"
        }
        val dacSubText = when {
            bridgeState.ratePlan != null && bridgeState.ratePlan.isHiResSubmixNative ->
                s.tileDacHiResNative(preferredBitDepth)
            bridgeState.ratePlan != null && bridgeState.ratePlan.isIntegerDownsampled ->
                s.tileDacIntDiv(bridgeState.ratePlan.integerDivisor, preferredBitDepth)
            bridgeState.active ->
                s.tileDacLossless(preferredBitDepth)
            else -> s.tileDacIdle(preferredBitDepth)
        }

        val bufMainText = if (telemetry.streaming || telemetry.captureMode) {
            "${telemetry.bufferedMs.roundToInt()} ms"
        } else {
            "-- ms"
        }
        val bufSubText = when {
            telemetry.streaming && telemetry.underruns == 0L && telemetry.transferErrors == 0L -> s.tileBufZeroUnderrun
            telemetry.capturePrefilling -> s.tileBufPrefilling
            telemetry.streaming -> s.tileBufUnderrun(telemetry.underruns)
            else -> s.tileBufIdle
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = s.tileSourceLabel,
                value = srcRateText,
                sub = srcSubText,
                highlight = bridgeState.active,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = s.tileDacLabel,
                value = dacRateText,
                sub = dacSubText,
                highlight = telemetry.streaming,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = s.tileBufLabel,
                value = bufMainText,
                sub = bufSubText,
                highlight = telemetry.streaming && telemetry.underruns == 0L,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Localized Live Peak dBFS Meter + Wrapping Path Summary
        val localizedStatusLine = when {
            bridgeState.active && bridgeState.ratePlan != null -> {
                val plan = bridgeState.ratePlan
                val srcKhz = formatKhz(plan.sourceSampleRate)
                val effKhz = formatKhz(plan.effectiveSampleRate)
                val fmt = bridgeState.dacHardware?.shortFormatTag ?: "S${preferredBitDepth}"
                val appTag = bridgeState.sourceSpec?.appLabel?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: ""
                val pathBody = when {
                    plan.isHiResSubmixNative -> s.pathSummaryHiResNative(srcKhz, effKhz, fmt)
                    plan.isIntegerDownsampled -> s.pathSummaryIntDiv(srcKhz, effKhz, plan.integerDivisor, fmt)
                    else -> s.pathSummaryZeroSrc(srcKhz, effKhz, fmt)
                }
                appTag + pathBody
            }
            bridgeState.active ->
                if (operatingMode == BridgeOperatingMode.SHIZUKU_AUTO) s.statusStreamingAuto else s.statusStreamingManual
            else -> s.statusIdle
        }

        PeakLevelBar(
            peakDbfs = telemetry.peakDbfs,
            active = telemetry.streaming,
            statusLine = localizedStatusLine
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Primary Equal-Height 2-Button Action Row (46.dp)
        val canStart = connectionState.isReadyToStartBridge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (bridgeState.active) {
                UniformButton(
                    text = s.btnStopBridge,
                    onClick = onStopBridge,
                    height = 46.dp,
                    style = BtnStyle.Danger,
                    modifier = Modifier.weight(1f)
                )
            } else {
                UniformButton(
                    text = s.btnStartBridge,
                    onClick = onStartBridge,
                    enabled = canStart,
                    height = 46.dp,
                    style = BtnStyle.Primary,
                    modifier = Modifier.weight(1f)
                )
            }

            val launchBtnLabel = if (!preferredLaunchLabel.isNullOrBlank()) {
                s.btnOpenPlayerNamed(preferredLaunchLabel)
            } else {
                s.btnOpenPlayerDefault
            }
            UniformButton(
                text = launchBtnLabel,
                onClick = onOpenPlayerOrPicker,
                height = 46.dp,
                style = BtnStyle.Secondary,
                modifier = Modifier.weight(1f)
            )
        }

        if (bridgeState.active) {
            Spacer(modifier = Modifier.height(8.dp))
            UniformButton(
                text = s.btnQuickResync,
                onClick = onQuickResync,
                height = 36.dp,
                style = BtnStyle.Ghost,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PlayerScopeStrip(
    s: UsbBridgeStrings,
    captureScopeMode: CaptureScopeMode,
    selectedApp: InstalledAudioApp?,
    trackedPlayer: ResolvedUidApp?,
    bridgeState: AudioBridgeState,
    onOpenAppPicker: () -> Unit
) {
    if (captureScopeMode == CaptureScopeMode.SINGLE_APP) {
        val borderColor = if (selectedApp != null) CyanBorder.copy(alpha = 0.7f) else AmberBorder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(TileBg)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenAppPicker)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppIconBadge(icon = selectedApp?.icon, fallbackText = selectedApp?.label?.take(1) ?: "?")
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (selectedApp != null) {
                    Text(
                        text = selectedApp.label,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 17.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = s.scopeSingleLockedSub(selectedApp.packageName, selectedApp.uid),
                        color = TextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        style = TightTextStyle
                    )
                } else {
                    Text(
                        text = s.scopeSingleUnsetTitle,
                        color = AmberWarn,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = s.scopeSingleUnsetSub,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        style = TightTextStyle
                    )
                }
            }
            UniformButton(
                text = if (selectedApp != null) s.btnChangeApp else s.btnPickApp,
                onClick = onOpenAppPicker,
                height = 32.dp,
                style = if (selectedApp != null) BtnStyle.Secondary else BtnStyle.Primary,
                modifier = Modifier.width(78.dp)
            )
        }
    } else {
        val activeTracked = trackedPlayer
        val liveRateText = if (bridgeState.active && bridgeState.ratePlan != null) {
            val plan = bridgeState.ratePlan
            val src = formatKhz(plan.sourceSampleRate)
            val eff = formatKhz(plan.effectiveSampleRate)
            if (plan.sourceSampleRate != plan.effectiveSampleRate) "$src -> $eff" else eff
        } else {
            bridgeState.sourceSpec?.sampleRate?.takeIf { it > 0 }?.let { formatKhz(it) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(TileBg)
                .border(
                    1.dp,
                    if (activeTracked != null && bridgeState.active) EmeraldBorder.copy(alpha = 0.65f) else TileBorder,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (activeTracked != null) {
                AppIconBadge(icon = activeTracked.icon, fallbackText = activeTracked.label.take(1))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = s.scopeTrackingTitle(activeTracked.label, liveRateText),
                        color = EmeraldLive,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = s.scopeTrackingSub(activeTracked.packageName),
                        color = TextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        style = TightTextStyle
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyanDimBg)
                        .border(1.dp, CyanBorder.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ALL",
                        color = CyanPrimary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        style = TightTextStyle
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = s.scopeGlobalIdleTitle,
                        color = TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = s.scopeGlobalIdleSub,
                        color = TextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.5.sp,
                        style = TightTextStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIconBadge(
    icon: ImageBitmap?,
    fallbackText: String,
    size: Dp = 32.dp
) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(CyanDimBg)
                .border(1.dp, CyanBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackText.uppercase(),
                color = CyanPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                style = TightTextStyle
            )
        }
    }
}

@Composable
private fun AppPickerDialog(
    s: UsbBridgeStrings,
    installedApps: List<InstalledAudioApp>,
    selectedPackageName: String?,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onSelectApp: (InstalledAudioApp) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) installedApps
        else installedApps.filter { app ->
            app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q)
        }
    }
    val audioApps = remember(filtered) { filtered.filter { it.isRecommendedAudio } }
    val otherApps = remember(filtered) { filtered.filter { !it.isRecommendedAudio } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 320.dp, max = 600.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = s.pickerTitle,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    style = TightTextStyle,
                    modifier = Modifier.weight(1f)
                )
                UniformButton(
                    text = s.btnRefresh,
                    onClick = onRefresh,
                    enabled = !isScanning,
                    height = 30.dp,
                    style = BtnStyle.Ghost,
                    modifier = Modifier.width(64.dp)
                )
                UniformButton(
                    text = s.pickerClose,
                    onClick = onDismiss,
                    height = 30.dp,
                    style = BtnStyle.Secondary,
                    modifier = Modifier.width(64.dp)
                )
            }

            // Search box (searches by app name OR package name)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TileBg)
                    .border(1.dp, CyanBorder.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = s.pickerSearchHint,
                        color = TextMuted,
                        fontSize = 12.sp,
                        style = TightTextStyle
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    cursorBrush = SolidColor(CyanPrimary),
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 13.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TileBg)
                    .border(1.dp, TileBorder, RoundedCornerShape(12.dp))
                    .padding(6.dp)
            ) {
                when {
                    isScanning && installedApps.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.pickerScanning, color = TextSecondary, fontSize = 12.5.sp, style = TightTextStyle)
                        }
                    }
                    filtered.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.pickerEmpty, color = TextMuted, fontSize = 12.5.sp, style = TightTextStyle)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (audioApps.isNotEmpty()) {
                                item(key = "hdr_audio") {
                                    Text(
                                        text = s.pickerSectionAudio(audioApps.size),
                                        color = CyanPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TightTextStyle,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                                items(audioApps, key = { "a_${it.packageName}" }) { app ->
                                    AppPickerRow(
                                        app = app,
                                        selected = app.packageName == selectedPackageName,
                                        selectedBadge = s.pickerSelectedBadge,
                                        onClick = { onSelectApp(app) }
                                    )
                                }
                            }
                            if (otherApps.isNotEmpty()) {
                                item(key = "hdr_other") {
                                    Text(
                                        text = s.pickerSectionAll(otherApps.size),
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TightTextStyle,
                                        modifier = Modifier.padding(
                                            start = 6.dp,
                                            end = 6.dp,
                                            top = if (audioApps.isNotEmpty()) 8.dp else 4.dp,
                                            bottom = 4.dp
                                        )
                                    )
                                }
                                items(otherApps, key = { "o_${it.packageName}" }) { app ->
                                    AppPickerRow(
                                        app = app,
                                        selected = app.packageName == selectedPackageName,
                                        selectedBadge = s.pickerSelectedBadge,
                                        onClick = { onSelectApp(app) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    app: InstalledAudioApp,
    selected: Boolean,
    selectedBadge: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CyanDimBg else CardBg)
            .border(1.dp, if (selected) CyanBorder else TileBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppIconBadge(icon = app.icon, fallbackText = app.label.take(1), size = 34.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = app.label,
                color = if (selected) CyanPrimary else TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                style = TightTextStyle
            )
            Text(
                text = app.packageName,
                color = TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                style = TightTextStyle
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(EmeraldDimBg)
                    .border(1.dp, EmeraldBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = selectedBadge,
                    color = EmeraldLive,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TightTextStyle
                )
            }
        }
    }
}

@Composable
private fun GainAndBufferCard(
    s: UsbBridgeStrings,
    connectionState: DacConnectionState,
    volumeDb: Double,
    preferredBitDepth: Int,
    bufferDurationMs: Int,
    onVolumeChangeDb: (Double) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onSelectBitDepth: (Int) -> Unit,
    onSelectBufferMs: (Int) -> Unit
) {
    val profile = (connectionState as? DacConnectionState.Connected)?.profile
    val minDb = (profile?.volumeMinDb ?: -60.0).coerceAtLeast(-80.0)
    val maxDb = profile?.volumeMaxDb ?: 0.0
    val hwVolAvailable = profile == null || profile.hasHardwareVolume

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionTitle(s.gainCardTitle)
            Text(
                text = "${"%+.1f".format(volumeDb).replace("+0.0", "0.0").replace("-0.0", "0.0")} dB",
                color = CyanPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = TightTextStyle
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UniformButton(
                text = "-0.5",
                onClick = {
                    onVolumeChangeDb((volumeDb - 0.5).coerceIn(minDb, maxDb))
                    onVolumeChangeFinished()
                },
                enabled = hwVolAvailable,
                height = 36.dp,
                style = BtnStyle.Ghost,
                modifier = Modifier.width(54.dp)
            )

            Slider(
                value = volumeDb.toFloat().coerceIn(minDb.toFloat(), maxDb.toFloat()),
                onValueChange = { v ->
                    val stepped = (v * 2f).roundToInt() / 2.0
                    onVolumeChangeDb(stepped.coerceIn(minDb, maxDb))
                },
                onValueChangeFinished = onVolumeChangeFinished,
                valueRange = minDb.toFloat()..maxDb.toFloat(),
                enabled = hwVolAvailable,
                colors = SliderDefaults.colors(
                    thumbColor = CyanPrimary,
                    activeTrackColor = CyanPrimary,
                    inactiveTrackColor = CardBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
            )

            UniformButton(
                text = "+0.5",
                onClick = {
                    onVolumeChangeDb((volumeDb + 0.5).coerceIn(minDb, maxDb))
                    onVolumeChangeFinished()
                },
                enabled = hwVolAvailable,
                height = 36.dp,
                style = BtnStyle.Ghost,
                modifier = Modifier.width(54.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        val volPresets = listOf(-30.0, -20.0, -10.0, 0.0)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            volPresets.forEach { presetDb ->
                val selected = kotlin.math.abs(volumeDb - presetDb) < 0.25
                PillOption(
                    text = if (presetDb == 0.0) s.preset0dB else "${presetDb.toInt()} dB",
                    selected = selected,
                    onClick = {
                        onVolumeChangeDb(presetDb.coerceIn(minDb, maxDb))
                        onVolumeChangeFinished()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = s.bitDepthLabel,
                color = TextSecondary,
                fontSize = 12.sp,
                style = TightTextStyle
            )
            Text(
                text = if (preferredBitDepth == 32) s.bitDepth32Hint else s.bitDepth24Hint,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                style = TightTextStyle
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        SegmentedRow(
            options = listOf(s.seg24Bit, s.seg32Bit),
            selectedIndex = if (preferredBitDepth == 32) 1 else 0,
            onSelect = { idx -> onSelectBitDepth(if (idx == 1) 32 else 24) },
            height = 36.dp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = s.spscLabel,
                color = TextSecondary,
                fontSize = 12.sp,
                style = TightTextStyle
            )
            Text(
                text = if (bufferDurationMs <= 85) {
                    s.spscLowLatencyHint(bufferDurationMs)
                } else {
                    s.spscTargetHint(bufferDurationMs)
                },
                color = if (bufferDurationMs <= 85) EmeraldLive else TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                style = TightTextStyle
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BUFFER_PRESETS_MS.forEach { ms ->
                PillOption(
                    text = "$ms ms",
                    selected = bufferDurationMs == ms,
                    onClick = { onSelectBufferMs(ms) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WorkbenchCard(
    s: UsbBridgeStrings,
    languagePref: LanguagePref,
    socProfile: SocCompatibilityProfile,
    remoteSubmixProfile: RemoteSubmixProfile?,
    onSelectLanguagePref: (LanguagePref) -> Unit,
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    logs: List<LogEntry>,
    telemetry: StreamTelemetry,
    selectedPreset: SineTestPreset,
    connectionState: DacConnectionState,
    onCopyLogs: () -> Unit,
    onDumpSnapshot: () -> Unit,
    onClearLogs: () -> Unit,
    onToggleSineTest: () -> Unit,
    onSelectSinePreset: (SineTestPreset) -> Unit,
    onClaimArmedUsbNow: () -> Unit,
    onCopyReport: (String) -> Unit
) {
    SectionCard {
        SegmentedRow(
            options = listOf(s.tabLogs(logs.size), s.tabSineTest, s.tabDevice),
            selectedIndex = activeTab,
            onSelect = onSelectTab,
            height = 36.dp
        )

        Spacer(modifier = Modifier.height(10.dp))

        when (activeTab) {
            0 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UniformButton(
                        text = s.btnCopyLogs,
                        onClick = onCopyLogs,
                        height = 34.dp,
                        style = BtnStyle.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    UniformButton(
                        text = s.btnSnapshot,
                        onClick = onDumpSnapshot,
                        height = 34.dp,
                        style = BtnStyle.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    UniformButton(
                        text = s.btnClear,
                        onClick = onClearLogs,
                        height = 34.dp,
                        style = BtnStyle.Ghost,
                        modifier = Modifier.width(68.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val listState = rememberLazyListState()
                val visibleLogs = remember(logs) { logs.takeLast(180) }
                LaunchedEffect(visibleLogs.size) {
                    if (visibleLogs.isNotEmpty()) {
                        listState.scrollToItem(visibleLogs.lastIndex)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TileBg)
                        .border(1.dp, TileBorder, RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    if (visibleLogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.emptyLogs, color = TextMuted, fontSize = 12.sp, style = TightTextStyle)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(visibleLogs) { entry ->
                                val color = when (entry.level) {
                                    3 -> CoralDanger
                                    2 -> AmberWarn
                                    0 -> TextMuted
                                    else -> TextSecondary
                                }
                                Text(
                                    text = "${entry.timestamp} [${entry.source}] ${entry.message}",
                                    color = color,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            1 -> {
                Text(
                    text = s.sineDesc,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    style = TightTextStyle
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SINE_PRESETS.forEach { preset ->
                        PillOption(
                            text = preset.label,
                            selected = selectedPreset.sampleRate == preset.sampleRate,
                            onClick = { onSelectSinePreset(preset) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                UniformButton(
                    text = if (telemetry.sineRunning) s.btnStopSine else s.btnStartSine,
                    onClick = onToggleSineTest,
                    enabled = connectionState.isReadyToStartBridge,
                    height = 42.dp,
                    style = if (telemetry.sineRunning) BtnStyle.Danger else BtnStyle.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            2 -> {
                Text(
                    text = s.langSettingLabel,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    style = TightTextStyle
                )
                Spacer(modifier = Modifier.height(6.dp))
                SegmentedRow(
                    options = listOf(s.langAuto, "中文", "English"),
                    selectedIndex = when (languagePref) {
                        LanguagePref.AUTO -> 0
                        LanguagePref.ZH -> 1
                        LanguagePref.EN -> 2
                    },
                    onSelect = { idx ->
                        onSelectLanguagePref(
                            when (idx) {
                                1 -> LanguagePref.ZH
                                2 -> LanguagePref.EN
                                else -> LanguagePref.AUTO
                            }
                        )
                    },
                    height = 36.dp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Detected SoC & HAL Strategy Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TileBg)
                        .border(1.dp, TileBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = s.socCardLabel,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        style = TightTextStyle
                    )
                    Text(
                        text = "${socProfile.deviceModel} · ${socProfile.socDisplayName} (${socProfile.rawBoardPlatform})",
                        color = CyanPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = socProfile.strategyDescription(s.isZh),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        style = TightTextStyle
                    )
                    Text(
                        text = if (remoteSubmixProfile != null) {
                            s.socSubmixLine(remoteSubmixProfile.rawSummary)
                        } else {
                            s.socSubmixDefault
                        },
                        color = TextMuted,
                        fontSize = 10.5.sp,
                        lineHeight = 14.5.sp,
                        style = TightTextStyle
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (connectionState) {
                    is DacConnectionState.Connected -> {
                        val report = connectionState.fullReport
                        UniformButton(
                            text = s.btnCopyReport,
                            onClick = { onCopyReport(report) },
                            height = 36.dp,
                            style = BtnStyle.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TileBg)
                                .border(1.dp, TileBorder, RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            val lines = remember(report) { report.lines() }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(lines) { line ->
                                    Text(
                                        text = line,
                                        color = TextSecondary,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    is DacConnectionState.ArmedStandby -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TileBg)
                                .border(1.dp, TileBorder, RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = s.standbyInspectDesc,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 17.sp,
                                style = TightTextStyle
                            )
                            UniformButton(
                                text = s.btnReadDescriptors,
                                onClick = onClaimArmedUsbNow,
                                height = 36.dp,
                                style = BtnStyle.Primary,
                                modifier = Modifier.width(190.dp)
                            )
                        }
                    }

                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 84.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TileBg)
                                .border(1.dp, TileBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = s.connectToViewTopology,
                                color = TextMuted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                style = TightTextStyle
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Reusable Zero-Clipping & Zero-Ellipsis Atomic UI Components
// ============================================================================

private enum class BtnStyle { Primary, Secondary, Ghost, Warning, Danger }

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun AutoFitSingleLineText(
    text: String,
    color: Color,
    maxFontSize: TextUnit,
    minFontSize: TextUnit = 9.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier
) {
    var fontSizeSp by remember(text, maxFontSize) { mutableFloatStateOf(maxFontSize.value) }
    Text(
        text = text,
        color = color,
        fontSize = fontSizeSp.sp,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        style = TightTextStyle,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSizeSp > minFontSize.value) {
                fontSizeSp = (fontSizeSp * 0.92f).coerceAtLeast(minFontSize.value)
            }
        },
        modifier = modifier
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        style = TightTextStyle
    )
}

@Composable
private fun UniformButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 42.dp,
    style: BtnStyle = BtnStyle.Primary
) {
    val (bg, border, fg) = if (!enabled) {
        Triple(TileBg, TileBorder, TextMuted)
    } else {
        when (style) {
            BtnStyle.Primary -> Triple(CyanPrimary, CyanPrimary, Color(0xFF041A24))
            BtnStyle.Secondary -> Triple(CyanDimBg, CyanBorder, CyanPrimary)
            BtnStyle.Ghost -> Triple(TileBg, CardBorder, TextSecondary)
            BtnStyle.Warning -> Triple(AmberWarn, AmberWarn, Color(0xFF1F1503))
            BtnStyle.Danger -> Triple(CoralDimBg, CoralBorder, CoralDanger)
        }
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = height)
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        AutoFitSingleLineText(
            text = text,
            color = fg,
            maxFontSize = 13.sp,
            minFontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    height: Dp = 38.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = height)
            .height(IntrinsicSize.Max)
            .clip(RoundedCornerShape(11.dp))
            .background(TileBg)
            .border(1.dp, TileBorder, RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = height - 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) CyanDimBg else Color.Transparent)
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) CyanBorder else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                AutoFitSingleLineText(
                    text = label,
                    color = if (selected) CyanPrimary else TextSecondary,
                    maxFontSize = 12.sp,
                    minFontSize = 9.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun RateOptionTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) CyanDimBg else TileBg)
            .border(1.dp, if (selected) CyanPrimary else TileBorder, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AutoFitSingleLineText(
            text = title,
            color = if (selected) CyanPrimary else TextPrimary,
            maxFontSize = 13.sp,
            minFontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        AutoFitSingleLineText(
            text = subtitle,
            color = if (selected) TextPrimary.copy(alpha = 0.88f) else TextMuted,
            maxFontSize = 10.5.sp,
            minFontSize = 8.5.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun PillOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) CyanDimBg else TileBg)
            .border(1.dp, if (selected) CyanBorder else TileBorder, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        AutoFitSingleLineText(
            text = text,
            color = if (selected) CyanPrimary else TextSecondary,
            maxFontSize = 11.5.sp,
            minFontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    sub: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = 86.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(TileBg)
            .border(
                1.dp,
                if (highlight) EmeraldBorder.copy(alpha = 0.65f) else TileBorder,
                RoundedCornerShape(11.dp)
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AutoFitSingleLineText(
            text = label,
            color = TextMuted,
            maxFontSize = 11.sp,
            minFontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
        AutoFitSingleLineText(
            text = value,
            color = if (highlight) EmeraldLive else TextPrimary,
            maxFontSize = 14.5.sp,
            minFontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        AutoFitSingleLineText(
            text = sub,
            color = TextSecondary,
            maxFontSize = 10.5.sp,
            minFontSize = 8.5.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun PeakLevelBar(
    peakDbfs: Double,
    active: Boolean,
    statusLine: String
) {
    val norm = if (!active || peakDbfs <= -72.0) {
        0f
    } else {
        ((peakDbfs + 72.0) / 72.0).toFloat().coerceIn(0.02f, 1f)
    }
    val dbLabel = if (!active || peakDbfs <= -115.0) "-∞ dBFS" else "${"%.1f".format(peakDbfs)} dBFS"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(TileBg)
            .border(1.dp, TileBorder, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusLine,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                style = TightTextStyle,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = dbLabel,
                color = if (active && peakDbfs > -60.0) CyanPrimary else TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                style = TightTextStyle
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(CardBorder)
        ) {
            if (norm > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(norm)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(CyanPrimary, EmeraldLive)
                            )
                        )
                )
            }
        }
    }
}

private fun formatKhz(rateHz: Int): String {
    if (rateHz <= 0) return "-- kHz"
    return if (rateHz % 1000 == 0) {
        "${rateHz / 1000}.0 kHz"
    } else {
        "${"%.1f".format(rateHz / 1000.0)} kHz"
    }
}

private fun compactUsbSpeed(rawSpeed: String): String {
    return when {
        rawSpeed.contains("High", ignoreCase = true) -> "HS 480M"
        rawSpeed.contains("Super", ignoreCase = true) -> "SS 5G"
        rawSpeed.contains("Full", ignoreCase = true) -> "FS 12M"
        else -> rawSpeed
    }
}
