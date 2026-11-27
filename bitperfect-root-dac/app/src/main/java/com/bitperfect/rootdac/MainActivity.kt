package com.bitperfect.rootdac

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.bitperfect.rootdac.i18n.AppLanguageManager
import com.bitperfect.rootdac.i18n.EnRootDacStrings
import com.bitperfect.rootdac.i18n.ZhRootDacStrings
import com.bitperfect.rootdac.root.AppleMusicSpecSniffer
import com.bitperfect.rootdac.ui.RootDacDashboardScreen
import com.bitperfect.rootdac.usb.RootDacManager
import com.topjohnwu.superuser.Shell

private val UnifiedHiFiDarkColors = darkColorScheme(
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

    private lateinit var rootDacManager: RootDacManager
    private var isRootGranted by mutableStateOf<Boolean?>(null)
    private var isBatteryOptIgnored by mutableStateOf(true)

    private fun currentStrings() =
        if (AppLanguageManager.isChinese(this)) ZhRootDacStrings else EnRootDacStrings

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        AppLanguageManager.init(this)
        requestNotificationPermissionIfNeeded()

        Shell.getShell { shell ->
            isRootGranted = shell.isRoot
        }

        rootDacManager = (application as BitPerfectRootApp).rootDacManager
        refreshBatteryOptStatus()

        setContent {
            MaterialTheme(colorScheme = UnifiedHiFiDarkColors) {
                val languagePref by AppLanguageManager.pref.collectAsState()
                val isZh = AppLanguageManager.resolveIsChinese(languagePref)
                val strings = if (isZh) ZhRootDacStrings else EnRootDacStrings

                val devices by rootDacManager.discoveredDevices.collectAsState()
                val connectionState by rootDacManager.connectionState.collectAsState()
                val telemetry by rootDacManager.telemetry.collectAsState()
                val bridgeState by rootDacManager.bridgeState.collectAsState()
                val engineMode by rootDacManager.engineMode.collectAsState()
                val lspStatus by rootDacManager.lspStatus.collectAsState()
                val snifferStatus by rootDacManager.snifferStatus.collectAsState()
                val selectedPreset by rootDacManager.selectedPreset.collectAsState()
                val volumeDb by rootDacManager.volumeDb.collectAsState()
                val bufferDurationMs by rootDacManager.bufferDurationMs.collectAsState()
                val preferredBridgeBitDepth by rootDacManager.preferredBridgeBitDepth.collectAsState()
                val logs by rootDacManager.logs.collectAsState()

                RootDacDashboardScreen(
                    strings = strings,
                    languagePref = languagePref,
                    isRootGranted = isRootGranted,
                    isBatteryOptIgnored = isBatteryOptIgnored,
                    devices = devices,
                    connectionState = connectionState,
                    telemetry = telemetry,
                    bridgeState = bridgeState,
                    engineMode = engineMode,
                    lspStatus = lspStatus,
                    snifferStatus = snifferStatus,
                    selectedPreset = selectedPreset,
                    volumeDb = volumeDb,
                    bufferDurationMs = bufferDurationMs,
                    preferredBridgeBitDepth = preferredBridgeBitDepth,
                    logs = logs,
                    onToggleLanguage = { AppLanguageManager.toggleLanguage(this) },
                    onSelectLanguagePref = { pref -> AppLanguageManager.setLanguagePref(this, pref) },
                    onRefreshDevices = { rootDacManager.refreshDevices() },
                    onSelectEngineMode = { mode -> rootDacManager.setEngineMode(mode) },
                    onOpenAppleMusic = { openAppleMusicApp() },
                    onRequestBatteryOptExemption = { requestIgnoreBatteryOptimizations() },
                    onConnectDevice = { rootDacManager.requestConnect(it.device) },
                    onDisconnect = { rootDacManager.disconnect() },
                    onStartRootBridge = { rootDacManager.startRootLoopbackBridge() },
                    onStopRootBridge = { rootDacManager.stopRootLoopbackBridge() },
                    onSelectBridgeBitDepth = { bits -> rootDacManager.setPreferredBridgeBitDepth(bits) },
                    onDumpSnifferSnapshot = {
                        rootDacManager.dumpSnifferDiagnosticSnapshot { snapshot ->
                            runOnUiThread {
                                copyToClipboard(
                                    "BitPerfect Root DAC Snapshot",
                                    snapshot,
                                    currentStrings().toastSnapshotCopied
                                )
                            }
                        }
                    },
                    onToggleSineTest = { rootDacManager.toggleSineTest() },
                    onSelectSinePreset = { preset -> rootDacManager.switchSinePreset(preset) },
                    onVolumeChangeDb = { db -> rootDacManager.setHardwareVolumeDb(db) },
                    onVolumeChangeFinished = { rootDacManager.commitHardwareVolumeDb() },
                    onBufferMsChange = { ms -> rootDacManager.setBufferDurationMs(ms) },
                    onClearLogs = { rootDacManager.clearLogs() },
                    onCopyLogs = {
                        val allLogs = rootDacManager.formatAllLogs()
                        copyToClipboard("BitPerfect Root DAC Logs", allLogs, currentStrings().toastLogsCopied)
                    },
                    onCopyReport = { report ->
                        copyToClipboard("USB DAC Profile", report, currentStrings().toastReportCopied)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptStatus()
    }

    private fun openAppleMusicApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(AppleMusicSpecSniffer.APPLE_MUSIC_PKG)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, currentStrings().toastAppleMusicMissing, Toast.LENGTH_SHORT).show()
        }
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
