package com.bitperfect.usbbridge.shizuku

import android.content.Context
import android.os.Process
import com.bitperfect.usbbridge.capture.InstalledAppScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DetectedAudioSpec(
    val sampleRate: Int,
    val bitDepth: Int,
    val sourceTag: String,
    val piid: Int = 0,
    val uid: Int = -1,
    val pid: Int = 0,
    val packageName: String? = null,
    val appLabel: String? = null,
    val detectedAtMs: Long = System.currentTimeMillis()
)

data class RemoteSubmixProfile(
    val supportedRates: List<Int>,
    val maxSupportedRate: Int,
    val supportsFloat: Boolean,
    val rawSummary: String
)

data class SnifferStatus(
    val running: Boolean = false,
    val targetUid: Int? = null, // null = Global Auto-Track across all media & game apps
    val activePlayerDetected: Boolean = false,
    val lastDetectedSpec: DetectedAudioSpec? = null,
    val note: String = "未启动"
)

/**
 * Universal Shizuku (uid=2000, ZERO Root) Multi-Player sample-rate sniffer & r_submix capability probe.
 *
 * Parses `dumpsys audio` every 800ms across ALL active `AudioPlaybackConfiguration ... state:started`
 * tracks (or a single locked UID), excluding system sounds and this app itself, resolving the active
 * player's app name & icon via PackageManager for real-time display and automatic DAC clock switching.
 */
class ShizukuSpecSniffer(
    context: Context,
    private val shizukuController: ShizukuController,
    private val onLog: (level: Int, msg: String) -> Unit
) {
    companion object {
        const val APPLE_MUSIC_PKG = "com.apple.android.music"
        private const val POLL_INTERVAL_MS = 800L

        private val VALID_RATES = setOf(
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
        )

        // Android 14-17 dumpsys audio exact format:
        // AudioPlaybackConfiguration piid:611855 ... u/pid:10362/24843 state:started attr:AudioAttributes: usage=USAGE_MEDIA ... FormatInfo{isSpatialized=false, channelMask=0x3, sampleRate=192000}
        private val FORMAT_INFO_RATE_REGEX = Regex("""FormatInfo\{[^}]*sampleRate=(\d+)""")
        private val PIID_REGEX = Regex("""piid:(\d+)""")
        private val UPID_REGEX = Regex("""u/pid:(\d+)/(\d+)""")
        private val USAGE_REGEX = Regex("""usage=([A-Z0-9_]+)""")
        private val SAMPLING_RATES_LINE_REGEX = Regex("""sampling rates:\s*([0-9,\s]+)""")

        private val IGNORED_USAGES = setOf(
            "USAGE_NOTIFICATION",
            "USAGE_NOTIFICATION_RINGTONE",
            "USAGE_NOTIFICATION_EVENT",
            "USAGE_NOTIFICATION_COMMUNICATION_REQUEST",
            "USAGE_NOTIFICATION_COMMUNICATION_INSTANT",
            "USAGE_NOTIFICATION_COMMUNICATION_DELAYED",
            "USAGE_ALARM",
            "USAGE_ASSISTANCE_SONIFICATION",
            "USAGE_ASSISTANCE_ACCESSIBILITY",
            "USAGE_VOICE_COMMUNICATION",
            "USAGE_VOICE_COMMUNICATION_SIGNALLING",
            "USAGE_ASSISTANT",
            "USAGE_CALL_ASSISTANT",
            "USAGE_EMERGENCY",
            "USAGE_SAFETY",
            "USAGE_ANNOUNCEMENT"
        )
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(SnifferStatus())
    val status: StateFlow<SnifferStatus> = _status.asStateFlow()

    @Volatile
    private var pollJob: Job? = null

    @Volatile
    private var lastCommittedSampleRate: Int = -1

    @Volatile
    private var lastActiveTrackPiid: Int = -1

    @Volatile
    private var lastActiveUid: Int = -1

    fun start(
        targetUid: Int?,
        isQualcommPal: Boolean,
        onSpecChanged: (DetectedAudioSpec) -> Unit
    ) {
        stop()
        lastCommittedSampleRate = -1
        lastActiveTrackPiid = -1
        lastActiveUid = -1

        val scopeLabel = if (targetUid != null && targetUid > 0) {
            val resolved = InstalledAppScanner.resolveUidToApp(appContext, targetUid)
            "单应用监听 ${resolved?.label ?: "UID=$targetUid"}"
        } else {
            "全局多播放器自动追踪 (USAGE_MEDIA / GAME)"
        }

        _status.value = SnifferStatus(
            running = true,
            targetUid = targetUid,
            note = "Shizuku $scopeLabel"
        )
        onLog(
            1,
            "Shizuku 通用采样率嗅探已启动 ($scopeLabel, 周期=${POLL_INTERVAL_MS}ms, 零 Root)"
        )

        pollJob = scope.launch {
            var lastLoggedPlaying = false
            while (isActive) {
                try {
                    if (!shizukuController.state.value.isReady) {
                        _status.value = _status.value.copy(
                            note = "等待 Shizuku UserService 就绪..."
                        )
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    val output = shizukuController.dumpAudioPlayback(targetUid ?: -1)
                    if (output != null) {
                        val spec = parseBestActiveTrack(output, targetUid)
                        if (spec == null) {
                            if (lastLoggedPlaying) {
                                onLog(1, "当前无活跃媒体/音游音轨 (waiting for state:started)")
                                lastLoggedPlaying = false
                            }
                            _status.value = _status.value.copy(
                                activePlayerDetected = false,
                                note = if (targetUid != null && targetUid > 0) {
                                    "等待目标应用播放..."
                                } else {
                                    "等待任意音乐/音游应用播放..."
                                }
                            )
                        } else {
                            val appName = spec.appLabel ?: spec.packageName ?: "UID=${spec.uid}"
                            if (!lastLoggedPlaying || spec.uid != lastActiveUid) {
                                onLog(
                                    1,
                                    "检测到活跃播放器: $appName (UID=${spec.uid}, PID=${spec.pid}, piid=${spec.piid}, ${spec.sampleRate} Hz)"
                                )
                                lastLoggedPlaying = true
                            }

                            val rateChanged = (spec.sampleRate != lastCommittedSampleRate)
                            val playerChanged = (spec.uid != lastActiveUid)
                            val piidChanged = (spec.piid != lastActiveTrackPiid)
                            lastActiveTrackPiid = spec.piid
                            lastActiveUid = spec.uid

                            _status.value = SnifferStatus(
                                running = true,
                                targetUid = targetUid,
                                activePlayerDetected = true,
                                lastDetectedSpec = spec,
                                note = "$appName · ${"%.1f".format(spec.sampleRate / 1000.0)} kHz"
                            )

                            if (rateChanged) {
                                lastCommittedSampleRate = spec.sampleRate
                                onLog(
                                    1,
                                    "★ Shizuku 嗅探到 [$appName] 原始采样率: ${spec.sampleRate} Hz (piid=${spec.piid})"
                                )
                                onSpecChanged(spec)
                            } else if (piidChanged && spec.sampleRate > 48000 && isQualcommPal) {
                                // Qualcomm PAL Hi-Res track switch (e.g. 192k -> 192k new AudioTrack piid):
                                // Qualcomm PAL renegotiates Hi-Res stream; trigger clean rebind!
                                onLog(
                                    1,
                                    "★ Shizuku 检测到 [$appName] Hi-Res 新音轨 (${spec.sampleRate} Hz, piid=${spec.piid}) -> 触发高通 PAL 防卡顿重绑定"
                                )
                                onSpecChanged(spec)
                            } else if (playerChanged) {
                                // Different player took over at same sample rate -> update UI metadata seamlessly
                                onSpecChanged(spec)
                            } else if (piidChanged) {
                                onLog(
                                    0,
                                    "[$appName] 同采样率切歌: ${spec.sampleRate} Hz (piid=${spec.piid}), 保持硬件无缝输出"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    onLog(2, "Shizuku 采样率轮询异常: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        lastCommittedSampleRate = -1
        lastActiveTrackPiid = -1
        lastActiveUid = -1
        _status.value = _status.value.copy(
            running = false,
            activePlayerDetected = false,
            note = "已停止"
        )
    }

    fun probeRemoteSubmixProfile(): RemoteSubmixProfile {
        val raw = shizukuController.dumpAudioPolicySubmix()
        if (raw.isNullOrBlank()) {
            return RemoteSubmixProfile(
                supportedRates = listOf(44100, 48000),
                maxSupportedRate = 48000,
                supportsFloat = false,
                rawSummary = "默认安全档位 [44100, 48000] Hz (r_submix 保守上限)"
            )
        }

        val rates = linkedSetOf<Int>()
        val supportsFloat = raw.contains("AUDIO_FORMAT_PCM_FLOAT", ignoreCase = true)
        for (line in raw.lineSequence()) {
            val match = SAMPLING_RATES_LINE_REGEX.find(line) ?: continue
            match.groupValues[1].split(",").forEach { token ->
                val hz = token.trim().toIntOrNull()
                if (hz != null && hz in VALID_RATES) {
                    rates.add(hz)
                }
            }
        }

        val sorted = if (rates.isNotEmpty()) rates.sorted() else listOf(44100, 48000)
        val maxRate = sorted.maxOrNull() ?: 48000
        val summary = "r_submix HAL 原生支持: $sorted Hz (最高 $maxRate Hz, float=${if (supportsFloat) "Y" else "N"})"
        onLog(1, "Shizuku 探测到系统 $summary")
        return RemoteSubmixProfile(
            supportedRates = sorted,
            maxSupportedRate = maxRate,
            supportsFloat = supportsFloat,
            rawSummary = summary
        )
    }

    private data class CandidateTrack(
        val uid: Int,
        val pid: Int,
        val piid: Int,
        val sampleRate: Int,
        val usagePriority: Int
    )

    /**
     * Parses `dumpsys audio` lines and selects the best active user media/game playback track.
     * When `targetUid` is null, tracks ALL non-system user apps (excluding ourselves).
     */
    fun parseBestActiveTrack(output: String, targetUid: Int?): DetectedAudioSpec? {
        val selfUid = Process.myUid()
        var best: CandidateTrack? = null

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (!line.contains("state:started")) continue

            val upidMatch = UPID_REGEX.find(line) ?: continue
            val uid = upidMatch.groupValues[1].toIntOrNull() ?: continue
            val pid = upidMatch.groupValues[2].toIntOrNull() ?: 0

            // Always exclude ourselves (e.g. DeepBufferKeepAliveGuard AudioTrack)
            if (uid == selfUid) continue

            if (targetUid != null && targetUid > 0) {
                if (uid != targetUid) continue
            } else {
                // Global mode: exclude core system UIDs (< 10000)
                if (uid < 10000) continue
            }

            val usage = USAGE_REGEX.find(line)?.groupValues?.getOrNull(1)
            if (usage != null && usage in IGNORED_USAGES) {
                continue
            }

            val rateMatch = FORMAT_INFO_RATE_REGEX.find(line) ?: continue
            val rate = rateMatch.groupValues[1].toIntOrNull() ?: continue
            if (rate !in VALID_RATES) continue

            val piid = PIID_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val usagePriority = when (usage) {
                "USAGE_MEDIA" -> 3
                "USAGE_GAME" -> 2
                else -> 1
            }

            val candidate = CandidateTrack(
                uid = uid,
                pid = pid,
                piid = piid,
                sampleRate = rate,
                usagePriority = usagePriority
            )

            val prev = best
            if (prev == null ||
                candidate.usagePriority > prev.usagePriority ||
                (candidate.usagePriority == prev.usagePriority && candidate.piid >= prev.piid)
            ) {
                best = candidate
            }
        }

        val chosen = best ?: return null
        val resolved = InstalledAppScanner.resolveUidToApp(appContext, chosen.uid)
        return DetectedAudioSpec(
            sampleRate = chosen.sampleRate,
            bitDepth = 24,
            sourceTag = "shizuku:${resolved?.packageName ?: "uid=${chosen.uid}"}@${chosen.sampleRate}Hz",
            piid = chosen.piid,
            uid = chosen.uid,
            pid = chosen.pid,
            packageName = resolved?.packageName,
            appLabel = resolved?.label
        )
    }
}
