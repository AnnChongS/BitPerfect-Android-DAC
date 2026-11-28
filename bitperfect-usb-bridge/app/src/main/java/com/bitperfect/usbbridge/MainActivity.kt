package com.bitperfect.usbbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.bitperfect.usbbridge.i18n.AppLanguageManager
import com.bitperfect.usbbridge.i18n.EnUsbBridgeStrings
import com.bitperfect.usbbridge.i18n.ZhUsbBridgeStrings
import com.bitperfect.usbbridge.ui.UsbBridgeDashboardScreen
import com.bitperfect.usbbridge.usb.CaptureScopeMode
import com.bitperfect.usbbridge.usb.UsbDacManager

private val BridgeDarkColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF041A24),
    secondary = Color(0xFF2AD6A5),
    onSecondary = Color(0xFF052E1E),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF070B12),
    surface = Color(0xFF0E1522),
    surfaceVariant = Color(0xFF162234),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFFB7185)
)

class MainActivity : ComponentActivity() {

    private lateinit var usbDacManager: UsbDacManager
    private var isBatteryOptIgnored by mutableStateOf(true)

    private fun currentStrings() =
        if (AppLanguageManager.isChinese(this)) ZhUsbBridgeStrings else EnUsbBridgeStrings

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission is optional; FGS still runs */ }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchMediaProjectionConsent()
        } else {
            Toast.makeText(
                this,
                currentStrings().toastNeedRecordPerm,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            usbDacManager.onMediaProjectionGranted(result.resultCode, data)
        } else {
            Toast.makeText(this, currentStrings().toastMpCancelled, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        AppLanguageManager.init(this)
        requestNotificationPermissionIfNeeded()

        usbDacManager = (application as BitPerfectApp).usbDacManager
        refreshBatteryOptStatus()

        setContent {
            MaterialTheme(colorScheme = BridgeDarkColors) {
                val languagePref by AppLanguageManager.pref.collectAsState()
                val isZh = AppLanguageManager.resolveIsChinese(languagePref)
                val strings = if (isZh) ZhUsbBridgeStrings else EnUsbBridgeStrings

                val remoteSubmixProfile by usbDacManager.remoteSubmixProfile.collectAsState()
                val shizukuState by usbDacManager.shizukuState.collectAsState()
                val operatingMode by usbDacManager.operatingMode.collectAsState()
                val captureScopeMode by usbDacManager.captureScopeMode.collectAsState()
                val selectedApp by usbDacManager.selectedApp.collectAsState()
                val trackedPlayer by usbDacManager.trackedPlayer.collectAsState()
                val installedApps by usbDacManager.installedApps.collectAsState()
                val isScanningApps by usbDacManager.isScanningApps.collectAsState()
                val manualSampleRate by usbDacManager.manualSampleRate.collectAsState()
                val systemMusicVolume by usbDacManager.systemMusicVolume.collectAsState()
                val xringHubDisclaimerActive by usbDacManager.xringHubDisclaimerActive.collectAsState()
                val xringHubDisclaimerAccepted by usbDacManager.xringHubDisclaimerAccepted.collectAsState()
                val xringDirectOverrideActive by usbDacManager.xringDirectOverrideActive.collectAsState()
                val devices by usbDacManager.discoveredDevices.collectAsState()
                val connectionState by usbDacManager.connectionState.collectAsState()
                val telemetry by usbDacManager.telemetry.collectAsState()
                val bridgeState by usbDacManager.bridgeState.collectAsState()
                val snifferStatus by usbDacManager.snifferStatus.collectAsState()
                val selectedPreset by usbDacManager.selectedPreset.collectAsState()
                val volumeDb by usbDacManager.volumeDb.collectAsState()
                val bufferDurationMs by usbDacManager.bufferDurationMs.collectAsState()
                val preferredBridgeBitDepth by usbDacManager.preferredBridgeBitDepth.collectAsState()
                val logs by usbDacManager.logs.collectAsState()

                val launchTarget = remember(captureScopeMode, selectedApp, trackedPlayer, bridgeState, installedApps) {
                    usbDacManager.resolvePreferredLaunchTarget()
                }

                UsbBridgeDashboardScreen(
                    strings = strings,
                    languagePref = languagePref,
                    socProfile = usbDacManager.socProfile,
                    remoteSubmixProfile = remoteSubmixProfile,
                    shizukuState = shizukuState,
                    operatingMode = operatingMode,
                    captureScopeMode = captureScopeMode,
                    selectedApp = selectedApp,
                    trackedPlayer = trackedPlayer,
                    installedApps = installedApps,
                    isScanningApps = isScanningApps,
                    preferredLaunchLabel = launchTarget?.second,
                    manualSampleRate = manualSampleRate,
                    systemMusicVolume = systemMusicVolume,
                    isBatteryOptIgnored = isBatteryOptIgnored,
                    xringHubDisclaimerActive = xringHubDisclaimerActive,
                    xringHubDisclaimerAccepted = xringHubDisclaimerAccepted,
                    xringDirectOverrideActive = xringDirectOverrideActive,
                    devices = devices,
                    connectionState = connectionState,
                    telemetry = telemetry,
                    bridgeState = bridgeState,
                    snifferStatus = snifferStatus,
                    selectedPreset = selectedPreset,
                    volumeDb = volumeDb,
                    bufferDurationMs = bufferDurationMs,
                    preferredBridgeBitDepth = preferredBridgeBitDepth,
                    logs = logs,
                    onToggleLanguage = { AppLanguageManager.toggleLanguage(this) },
                    onSelectLanguagePref = { pref -> AppLanguageManager.setLanguagePref(this, pref) },
                    onAcceptXringHubDisclaimer = { usbDacManager.acceptXringHubDisclaimer() },
                    onUnlockXringDirectForTesting = { usbDacManager.unlockXringDirectForTesting() },
                    onRefreshDevices = { usbDacManager.refreshDevices() },
                    onRefreshShizuku = { usbDacManager.refreshShizukuStatus() },
                    onRequestShizukuPermission = { usbDacManager.requestShizukuPermission() },
                    onSelectOperatingMode = { mode -> usbDacManager.setOperatingMode(mode) },
                    onSelectCaptureScopeMode = { scope -> usbDacManager.setCaptureScopeMode(scope) },
                    onSelectTargetApp = { app -> usbDacManager.selectTargetApp(app) },
                    onRefreshInstalledApps = { usbDacManager.refreshInstalledApps() },
                    onSelectManualSampleRate = { rate -> usbDacManager.selectManualSampleRate(rate) },
                    onMaximizeSystemVolume = { usbDacManager.maximizeSystemMusicVolume() },
                    onRequestBatteryOptExemption = { requestIgnoreBatteryOptimizations() },
                    onOpenPreferredPlayer = { openPreferredPlayerApp() },
                    onConnectDevice = { usbDacManager.requestConnect(it.device) },
                    onClaimArmedUsbNow = { usbDacManager.claimArmedUsbNow() },
                    onDisconnect = { usbDacManager.disconnect() },
                    onStartAudioBridge = { requestStartAudioBridge() },
                    onStopAudioBridge = { usbDacManager.stopAudioBridge() },
                    onQuickResync = { usbDacManager.quickResyncBridge() },
                    onSelectBridgeBitDepth = { bits -> usbDacManager.setPreferredBridgeBitDepth(bits) },
                    onDumpAndCopySnapshot = {
                        usbDacManager.dumpDiagnosticSnapshot { snapshot ->
                            runOnUiThread {
                                copyToClipboard(
                                    "BitPerfect Diagnostic Snapshot",
                                    snapshot,
                                    currentStrings().toastSnapshotCopied
                                )
                            }
                        }
                    },
                    onToggleSineTest = { usbDacManager.toggleSineTest() },
                    onSelectSinePreset = { preset -> usbDacManager.switchSinePreset(preset) },
                    onVolumeChangeDb = { db -> usbDacManager.setHardwareVolumeDb(db) },
                    onVolumeChangeFinished = { usbDacManager.commitHardwareVolumeDb() },
                    onBufferMsChange = { ms -> usbDacManager.setBufferDurationMs(ms) },
                    onClearLogs = { usbDacManager.clearLogs() },
                    onCopyLogs = {
                        val allLogs = usbDacManager.formatAllLogs()
                        copyToClipboard("BitPerfect Real-Time Logs", allLogs, currentStrings().toastLogsCopied)
                    },
                    onCopyReport = { report ->
                        copyToClipboard("USB DAC Report", report, currentStrings().toastReportCopied)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptStatus()
        usbDacManager.refreshShizukuStatus()
        usbDacManager.refreshSystemMusicVolume()
    }

    private fun refreshBatteryOptStatus() {
        val pm = getSystemService(PowerManager::class.java)
        isBatteryOptIgnored = pm?.isIgnoringBatteryOptimizations(packageName) == true
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun openPreferredPlayerApp() {
        val target = usbDacManager.resolvePreferredLaunchTarget()
        if (target == null) {
            Toast.makeText(this, currentStrings().toastPickAppFirst, Toast.LENGTH_SHORT).show()
            return
        }
        val (pkg, label) = target
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, currentStrings().toastAppMissing(label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStartAudioBridge() {
        if (usbDacManager.captureScopeMode.value == CaptureScopeMode.SINGLE_APP &&
            usbDacManager.selectedApp.value == null
        ) {
            Toast.makeText(this, currentStrings().toastPickAppFirst, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchMediaProjectionConsent()
    }

    private fun launchMediaProjectionConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun copyToClipboard(label: String, text: String, toastMsg: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }
}
